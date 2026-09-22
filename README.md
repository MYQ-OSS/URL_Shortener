# 高并发短链接系统

> Spring Boot 4.0.7 · Java 21 · MyBatis-Plus · MySQL 8 · Redis 7 (Redisson) · Caffeine · RabbitMQ
>
> 业务模型极简（短码 → 长链一张表），技术内核完整：发号、抗枚举短码、多级缓存、
> 防穿透/击穿、热点治理、异步统计、限流、风控。**每一个技术决策都记录在
> [docs/adr.md](docs/adr.md)——包括放弃了什么、为什么。**

## 架构总览

```
 创建链路（QPS 低，刻意做"重"）
 POST /api/link ─► 限流(gid) ─► 风控(开放重定向/SSRF) ─► 幂等(url_hash)
      ─► 随机短码 + BF判重（先写 BF 后落库，ADR-004）─► 落库(唯一索引兜底)
      ─► 预热缓存 ─► 返回（返回即可 302，绝无 404 窗口）

 跳转链路（QPS 极高，必须"瘦"）
 GET /{6位短码} ─► 限流(IP+码) ─► Caffeine L1 ─► Redis L2 ─► BloomFilter
      ─► 空值缓存 ─► 分布式锁+双重检查 ─► MySQL ─► 回写缓存 ─► 302
      └─► 统计事件异步投 RabbitMQ（失败降级本地暂存，绝不影响跳转，ADR-009）

 统计链路（最终一致）
 MQ ─► 消费端（msgId 幂等 + Redis INCR/PV + HLL/UV/UIP + dirty 集合）
    ─► 30s 定时任务（Redisson 锁）─► GETDEL 增量 ─► MySQL 批量 upsert
```

## 快速启动

### 方式 A：Docker（推荐，可复现压测环境）

```bash
docker compose up -d          # MySQL 8 / Redis 7 / RabbitMQ，schema 自动执行
mvn spring-boot:run
```

### 方式 B：本机已有 MySQL/Redis

复制一份 `src/main/resources/application-local.yaml`（已 gitignore，含本机密码），
按需改数据源，然后：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

RabbitMQ 缺席时应用照常启动，统计链路自动降级本地暂存（`/admin/stats` 里 `stats.pending` 会增长）。

## 接口速览

| 接口 | 说明 |
|---|---|
| `POST /api/link` | 创建短链。`{originUrl, gid, forceNew?, validType?, validEnd?, describe?}`；默认按 (gid, url_hash) 幂等，`forceNew=true` 强制新码（渠道归因） |
| `GET /{shortUri}` | 302 跳转；不存在/禁用/过期 → 410 |
| `POST /api/link/{uri}/disable` / `enable` | 禁用/恢复：先改 DB 再清多级缓存 + Pub/Sub 广播，最坏 5s 生效（短 TTL 兜底） |
| `POST /admin/bloom/rebuild` | 双 BF 滚动重建（压测灌数后必调，ADR-013） |
| `POST /admin/cache/local/clear` | 清本地缓存（每轮压测前调） |
| `GET /admin/stats` | L1 命中率 / 发号器水位 / BF 版本 / 统计链路健康度 |
| `GET /actuator/prometheus` | Micrometer 指标 |

## 已验证（2026-07-26 冒烟 + 2026-08-03/04 压测/统计/故障演练实测）

- ✅ `mvn compile` + 17 个纯逻辑单测全绿（Base62 / Feistel / 发号器 1000 线程并发 / 号段存储）
- ✅ 端到端：创建 → 302；幂等同码；forceNew 新码；内网 URL 风控 400；
  不存在短码 410（BF 拦截）；非法格式 404；disable → 410 → enable → 302
- ✅ **Week 2 压测数据已采集**（Zipf α=1.0，100 万数据，k6 200VU）：
  裸 MySQL 10,640 → +Caffeine L1 **16,117 QPS（+51%）**，P50 16.3→8.6ms，
  L1 命中率 63.7%（整体命中率 ~100%）。数据与归因见 [benchmark/results/baseline.md](benchmark/results/baseline.md)
- ✅ **Week 3 统计链路运行时验证**：PV 对账误差 **0.00%**（10,000 次跳转精确落库，
  日汇总=小时合计=click_num）；MQ 宕机→缓冲→恢复→补投全链路无损
- ✅ **Week 4 分库分表接入**：t_link 按 short_uri 分 2 库 × 8 表，t_link_goto 按 gid 分片，
  本地消息表同库事务双写。sql-show 实测跳转/创建/列表**全程零广播**；
  100 万存量数据纯 SQL 迁移后逐分片校验一致（ADR-024）
- ✅ **Week 5 故障演练实测**（ADR-015）：MQ 宕机跳转不受影响（修复前 155 QPS 阻塞 bug
  由压测抓出 → 异步 publisher + 熔断）；Redis 宕机**错误率 0%**（降级直查 DB）；
  MySQL 宕机热数据 **98.96% 可用**（数据不可变 + 30 天 TTL 缓存兜底）
- ✅ **Week 5 统计异步批量投递**（ADR-023）：完整形态 QPS **1,185 → 12,831（10.8x）**，
  常规负载统计无损（300 次跳转 → pv 精确 300）
- ✅ **Week 5 可观测 + 风控**：Prometheus 域指标 9 项（L1 命中率/淘汰/统计 pending/dropped/
  发号器水位/BF 版本）；域名黑白名单（后缀匹配，子域名换皮不误伤，已实测）
- ✅ ShardingSphere 5.5.3 x Boot 4 冒烟通过：按分片键单表路由 / 按 gid 广播 8 表的
  对照日志齐全；**薄驱动需手动引 7 个模块**（ADR-017，模块清单在 pom.xml 注释里）
- 🐛 **压测实测发现并修复 8 个真 bug**（全部固化进 ADR-019~024）：
  ①创建链路连接池自饿（5→1305 QPS）②MQ 宕机阻塞请求线程（异步化+熔断）
  ③short_uri collation 大小写碰撞 ④commons-pool2 缺失致 Lettuce 池化失效
  ⑤统计落库 upsert 列二义 ⑥每请求同步 publish 瓶颈（1,185→12,831 QPS）
  ⑦Redis 宕机无降级路径 ⑧ShardingSphere 单表不自动路由（!SINGLE 规则）
- 🐛 早期实测发现并修复：幂等唯一索引与 forceNew 互斥（降级为普通索引，ADR-018）；
  catch-all 异常处理吞掉 404 语义（显式放行 NoResourceFoundException）

## 进度（5 周排期见 [docs/方案评审与重构.md](docs/方案评审与重构.md) Part 6）

- [x] Week 1：环境 + 发号器 + 短码 + 创建/跳转链路 + 冒烟
- [x] Week 2 代码 + **压测数据**：多级缓存 / BF 防穿透 / 分布式锁 / 限流
- [x] Week 3：统计链路（MQ 投递 + 消费 + 定时落库）**运行时验证通过（PV 误差 0%）**
- [x] Week 4：**ShardingSphere 2 库 × 8 表接入 + 异构索引表双写 + 本地消息表**（验收零广播，ADR-024）
- [x] Week 5：**故障演练三类宕机实测 + Prometheus 域指标 + 风控黑白名单 + 统计异步投递**（ADR-015/023）

## 文档

| 文档 | 内容 |
|---|---|
| [docs/adr.md](docs/adr.md) | 22 条架构决策记录：每条都有"考虑过的方案/放弃了什么/数据依据" |
| [docs/方案评审与重构.md](docs/方案评审与重构.md) | 完整方案评审、5 周排期、简历写法、37 道击穿题 |
| [docs/day1-smoke-test.md](docs/day1-smoke-test.md) | Day 1 检查清单 |
| [benchmark/](benchmark/) | Zipf 压测脚本（k6/wrk）、种子数据生成、命中率理论计算、结果（baseline.md） |
