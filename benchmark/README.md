# 单库主版本压测

历史结果见 [results/baseline.md](results/baseline.md)，只能说明原实验，不代表精简后的吞吐或统计无损能力。不要把预期数字、其他版本的数字直接写成当前版本实测。

## 准备独立环境

仅在独立测试数据库使用 `seed.sql`；它会操作辅助表和函数并写入大量记录，不要在业务库执行。先导入当前 `sql/schema.sql`，再检查种子数据与 `seedcode.py` 的短码规则一致。

主版本没有 Bloom，不需要重建过滤器，也不需要推进号段表。种子数值编码仅用于生成可复现的测试输入，与生产随机短码无关。

```powershell
# 项目根目录；使用明确的独立测试库和相应连接配置
python benchmark/gen_requests.py --rows 1000000 --requests 2000000 --alpha 1.0 --seed 20260726 -o benchmark/wrk/requests.txt
```

用同一份数据和同一份请求日志对比。分别测试热点、均匀请求和混合不存在短码；记录分布、缓存大小、TTL、数据量、CPU、内存、实例数和中间件配置。

## 跳转

```bash
wrk -t4 -c50 -d30s --latency -s benchmark/wrk/redirect.lua http://127.0.0.1:8080 -- benchmark/wrk/requests.txt
k6 run -e REQ_FILE=../wrk/requests.txt -e VUS=50 -e DURATION=30s benchmark/k6/redirect.js
```

不跟随 302，避免把目标网站延迟算入结果。分别报告 302、410、429、503 与其他错误；混合无效短码时，410 是预期结果，不应全部计作业务失败。脚本的 95% 提示适用于有效请求为主的日志。

缓存对比使用已有配置：

| 阶段 | l1-enabled | l2-enabled |
|---|---|---|
| 受保护的数据库回源 | false | false |
| Redis | false | true |
| Caffeine + Redis | true | true |

所有阶段都保留 DB 回源预算。默认 200 QPS、8 并发保护会限制裸 DB 阶段，不能把保护器吞吐当成数据库极限。需要调整时只在隔离压测环境修改 `shortlink.fallback`，并记录预算、连接池及拒绝比例。

跳转限流使用 `shortlink.rate-limit.redirect.capacity/refill-per-sec`，不存在 `rate-limit.enabled` 总开关。提高限额只用于隔离压测，不应照搬到部署环境。完整业务压测保留统计；单独对比读取路径可使用 `shortlink.stats.enabled=false`，并明确标注。

清理本地缓存或读取状态时通过管理员请求头鉴权，例如：

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/admin/cache/local/clear -Headers @{ "X-Admin-Token" = $env:SHORTLINK_ADMIN_TOKEN }
Invoke-RestMethod -Uri http://127.0.0.1:8080/admin/stats -Headers @{ "X-Admin-Token" = $env:SHORTLINK_ADMIN_TOKEN }
```

这些操作只影响当前实例；只清 L1 不等于整个系统冷启动。Redis 中可能有上轮预热和空值缓存，应在独立实例中准备一致的初始状态，不要对共享 Redis 执行 FLUSHALL。

## 创建与统计

创建脚本从环境变量读取 `SHORTLINK_API_TOKEN`、`SHORTLINK_API_GID`，默认 gid 为 `default`。保证 Token 与 gid 绑定匹配。脚本使用 `forceNew=true` 测真实创建，另做同 URL 并发防重测试。不要将鉴权或限流拒绝算成创建吞吐。

```bash
wrk -t2 -c10 -d30s --latency -s benchmark/wrk/create.lua http://127.0.0.1:8080
k6 run -e VUS=10 -e DURATION=30s benchmark/k6/create.js
```

依据目标流量逐步增加负载，没有固定的创建/访问比要求。观察 QPS、P95/P99、数据库错误、连接池、统计 pending/dropped/publishFailures。主版本不再暴露号段或 Bloom 指标。

访问统计允许少量损失：容量、进程关闭、崩溃、Redis 故障都可能影响数据。测试中对齐消费和落库时间再对账，记录事件丢弃及统计延迟，不要从 `dropped=0` 推出端到端无损。

## 结果

每次至少记录：提交版本、启动参数、数据量/分布、并发、总请求数、业务成功吞吐、状态码分布、P95/P99、CPU/内存、统计延迟与丢弃。一次只改一个主要变量，再用重复测试验证差异；本次精简没有重新跑性能压测。
