# 压测：方法论与操作手册

> **这个目录的产出，就是你简历上"高并发"三个字的唯一凭证。**
>
> 面试官问"你这个多少 QPS？怎么压的？瓶颈在哪？"——答不上来，前面所有技术点都作废。

---

## 0. 先记住三条铁律

**① 必须有基线。** 在加 Redis 之前先压一组"裸 MySQL"存档。
没有基线，所有"提升了 X 倍"都是空话，而且面试官一定会问"提升多少"。

**② 必须用 Zipf 分布，不能用均匀随机。**
真实短链流量极度长尾：少数热门链接占绝大部分点击。均匀随机每次都打一个几乎没被访问过的短码，
本地缓存永远不命中——**你测的是一个现实中不存在的场景，并且会系统性地低估缓存效果**。

实测（N=1000 万，本地缓存 10,000 条）：

| 请求分布 | L1 命中率 |
|---|---|
| 均匀随机 | **0.10%** |
| Zipf α=1.0 | **58.62%** |
| Zipf α=1.2 | **89.00%** |

差 586 倍。这个细节说出来，面试官立刻知道你真压过。

**③ 每一轮必须可复现。** 同一个 `--seed`、同一份 `requests.txt`、每轮前清一次本地缓存。
否则"优化前 / 优化后"打的不是同一批请求，对比没有意义。

---

## 1. 环境（必须写进报告，不写等于没数）

```
被压机：<CPU 型号> / 4 vCPU / 8 GB（Docker limit）
        Java 21, JVM -Xmx2g
        MySQL 8.0 / Redis 7 / RabbitMQ 3.13 均为独立容器
压测机：与被压机分离            ← 同机压测的数字没有意义，压测工具会抢 CPU
数据量：1,000,000 条（先跑通）→ 10,000,000 条（正式）
请求分布：Zipf α=1.0，seed=20260726
```

⚠️ **两个必须主动声明的"不诚实点"**，否则数字会被质疑：

1. `docker-compose.yml` 里 MySQL 开了 `innodb_flush_log_at_trx_commit=2`
   （牺牲"崩溃丢 1 秒事务"换写入吞吐）。生产绝不能这么设。
2. Redis 关了 AOF。同理。

**主动说出来比被问出来强一百倍。**

---

## 2. 操作流程

### 2.1 起环境 + 灌数据

```bash
docker compose up -d

# 灌 100 万条（先跑通）。要 1000 万就改 seed.sql 里的 @total_rows
docker exec -i sl-mysql mysql -uroot -proot123 shortlink < benchmark/seed.sql
```

seed.sql 末尾会自检 `COUNT(*) == COUNT(DISTINCT short_uri)`。不相等就是 `fn_b62` 写错了。

### 2.2 ★ 重建布隆过滤器（漏了这步整个压测就是废的）

```bash
mvn spring-boot:run          # 另开一个终端
curl -X POST http://localhost:8080/admin/bloom/rebuild
```

**为什么这步这么关键**：灌完种子数据后 BF 里并没有这些短码，
所有压测请求都会被 BF 判定为"一定不存在"→ 直接 404。
**这个坑极其隐蔽——QPS 会高得离谱（因为根本没查缓存和 DB），你会误以为优化大成功。**

判断方法：wrk 输出里 302 占比必须 > 95%。`redirect.lua` 会自动检查并报警。

### 2.3 生成请求日志

```bash
cd benchmark

# 先算理论值，压完拿实测对照
python3 cache_math.py --rows 1000000

# 生成 200 万条 Zipf 请求
python3 gen_requests.py --rows 1000000 --requests 2000000 --alpha 1.0

# 另外生成一份掺 5% 不存在短码的，用来单独验证防穿透链路
python3 gen_requests.py --rows 1000000 --requests 500000 --alpha 1.0 \
        --miss-ratio 0.05 -o wrk/requests_miss.txt
```

### 2.4 压

```bash
# 每一轮前先清本地缓存，保证冷启动一致
curl -X POST http://localhost:8080/admin/cache/local/clear

wrk -t8 -c200 -d60s --latency -s wrk/redirect.lua http://127.0.0.1:8080 -- wrk/requests.txt

# 压测中另开终端看内部指标，比 wrk 输出更能定位瓶颈
watch -n2 'curl -s localhost:8080/admin/stats'
```

⚠️ **压测时先把跳转限流调大或关掉**（`shortlink.rate-limit.redirect.refill-per-sec`），
否则你测的是限流器的吞吐而不是跳转链路的吞吐。看 wrk 输出里 429 的数量就知道有没有被挡。

⚠️ **务必关掉 SQL 日志和跳转链路的 INFO 日志**（`application.yaml` 里已默认关闭）。
开着它 QPS 会掉一大截，而且你会误以为瓶颈在 DB。

### 2.5 逐步优化，每步存一组数

这张表就是你简历上那几个数字的来源。**必须一步一步来，每步只改一个变量。**

| # | 阶段 | 改了什么 | 预期瓶颈 |
|---|---|---|---|
| ① | 裸 MySQL | 注掉 Redis/Caffeine，直查 DB | DB 连接池 + 磁盘 IO |
| ② | + Redis L2 | 打开 Redis 缓存 | 网络 RT + 序列化 |
| ③ | + Caffeine L1 | 打开本地缓存（maximumSize=100_000） | Tomcat 线程 + 日志 |
| ④ | + 连接池/线程池调优 | `maximum-pool-size`、Tomcat `threads.max` | 同步日志刷盘 |
| ⑤ | + 日志异步化 | Logback `AsyncAppender` / 跳转链路降到 WARN | CPU 饱和 |
| ⑥ | + 热点探测（Week 2） | 动态提升热点到本地缓存并延长 TTL | — |

**每一步都要抓一次火焰图**，否则"瓶颈在哪"就只能靠猜：

```bash
# 下载 async-profiler，压测跑起来后：
./profiler.sh -d 30 -f flame-stage3.html <pid>
```

火焰图会给你至少一个真实发现。最常见的三个：

1. **同步日志刷盘**（Logback 默认同步 + 每次跳转打一行 INFO）→ 常见提升 15-30%
2. **JSON 序列化**（缓存里存对象而不是纯字符串）→ 直接存 `origin_url` 字符串
3. **`Tomcat threads.max` / HikariCP `maximumPoolSize` 默认值不匹配**

> **"我用 async-profiler 定位到同步日志刷盘是瓶颈，改异步 Appender 后 QPS 提升 22%"**
> —— 这种真实发现比任何八股都值钱，因为它无法伪造。

---

## 3. 压创建链路（另一组，参数完全不同）

```bash
wrk -t4 -c50 -d30s --latency -s wrk/create.lua http://127.0.0.1:8080
```

**并发要开小（-c50 不是 -c200）。** 理由不是它扛不住，而是**读写比是 1:500**——
用跳转的并发量去压创建，测的是一个不存在的场景。真实创建 QPS 在几百到几千。

压完看 `/admin/stats` 里的 `idGenerator.syncLoadCount`：
持续增长说明 step 太小、预加载赶不上消耗（见 ADR-002），要调大 `t_id_segment.step`。

---

## 4. 故障降级演练（Week 5，但现在就可以试一次）

**这一项有数据支撑的话，杀伤力极大。**

```bash
# 压测跑起来后，另开终端：
docker stop sl-redis      # 观察 QPS / 错误率变化，截图
docker start sl-redis

docker stop sl-mysql      # 跳转链路应该仍然可用！
docker start sl-mysql

docker stop sl-rabbitmq   # 跳转链路应该完全不受影响
docker start sl-rabbitmq
```

预期结论（要压出自己的真数字）：

| 停掉 | 跳转链路 | 说明 |
|---|---|---|
| Redis | QPS 下降但**错误率 0%** | Caffeine 兜住热点——这是本地缓存的第二个价值 |
| **MySQL** | **仍 100% 可用** | 短链数据不可变，缓存可设超长 TTL。**这是短链架构的真实优势，主动讲出来很亮** |
| RabbitMQ | **完全不受影响** | 统计降级为本地暂存。"统计丢一点可以，跳转挂了不行" |

---

## 5. 简历怎么写这些数字

❌ **不要写**："支撑百万 QPS"
→ 4C8G 单机做不到，一问就穿。

✅ **要写**（换成你自己压出来的真数字）：

```
Zipf(α=1.0) 分布压测下，4C8G 单机跳转 QPS 从 1.1k(裸 MySQL) 提升至 3.8w，
P99 从 86ms 降至 4ms，L1 本地缓存命中率 72%（整体缓存命中率 99.9%）；
用 async-profiler 定位到同步日志刷盘瓶颈，改异步 Appender 后再提升 22%。
```

三个关键点：

1. **标明分布和 α**——这是"真压过"的signature
2. **区分 L1 命中率和整体命中率**——混着说会被问穿（见 `cache_math.py` 的输出）
3. **数字更小但可信度高一百倍**

---

## 6. 文件说明

| 文件 | 作用 |
|---|---|
| `seed.sql` | 纯 SQL 数据 seeder，不需要任何驱动 |
| `seedcode.py` | 种子短码生成规则的 Python 参照实现 |
| `verify_consistency.py` | **交叉校验 Java / Python / SQL 三处短码规则一致**（已通过 5000 个样本） |
| `gen_requests.py` | Zipf 请求日志生成器（只用标准库） |
| `cache_math.py` | 算理论 L1 命中率与期望延迟，压前先算、压后对照 |
| `wrk/redirect.lua` | 压跳转链路，含状态码分布与自动异常提示 |
| `wrk/create.lua` | 压创建链路 |
| `results/baseline.md` | 逐阶段数据记录表（**照着填，别只存在脑子里**） |

**为什么要交叉校验三处短码规则**：Java `Base62Codec`、SQL `fn_b62`、Python `seedcode.py`
必须生成完全相同的短码。不一致的后果是压测请求全部 404，而你会以为是缓存或 BF 的问题——
**极难排查**。所以加了 `verify_consistency.py`，改任何一处都先跑它。
