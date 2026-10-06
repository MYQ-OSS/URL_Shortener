# 安全与可靠性说明

本文描述当前单库主版本。之前的分片、Bloom 和磁盘 outbox 方案已退出主链路，切换步骤见 [精简说明](simplification.md)。

## 身份与归属

必须注入独立随机的 `SHORTLINK_ADMIN_TOKEN`、`SHORTLINK_API_TOKEN`，均至少 32 字符。缺失、过短或重复配置会拒绝启动。不要将密钥写入前端构建、URL、日志或版本库。

- `/api/**` 使用 Bearer Token，默认绑定 `gid=default`；创建、列表必须匹配该身份。
- 启用/禁用 SQL 同时校验短码、gid 的精确大小写、未删除与非风控封禁状态。他人短码不能修改；风控封禁不能被用户恢复。
- `/admin/**`、监控和接口文档入口只接受 `X-Admin-Token`；不接受查询参数中的 Token。
- CORS 默认只允许本机两个 Vite 来源。可通过 `SHORTLINK_CORS_ORIGINS` 配置完整来源；只有合法 OPTIONS 预检不要求 Token，正式请求仍鉴权。
- 前端凭据仅驻留页面内存，刷新即清除；开发时服务地址留空使用同源代理。

## URL、有效期与缓存

目标只允许 HTTP/HTTPS。URL 校验包括域名黑白名单和配置启用时的私网地址限制，但不替代专业钓鱼检测服务。

`ClientIpResolver` 仅信任 `SHORTLINK_TRUSTED_PROXIES` 指定代理的转发头；默认按 socket 对端识别 IP，不能直接信任用户提供的 XFF。

有效期按北京时间解释，并与数据库 DATETIME 秒精度一致。缓存携带截止时间，L1/L2 命中后仍检查；正向 TTL 不超过实际截止时间和 30 天上限。旧版裸 URL 缓存被视为未命中，必须回源校验。

状态修改与缓存失效待办同事务提交。Lua 原子提升版本、删除正向/空值缓存并广播；旧查询快照不能重新覆盖缓存。其他实例漏收广播由默认 5 秒本地 TTL 兜底。Redis 故障后由 `CacheInvalidationRetryJob` 重试清理 L2，不承诺故障期间固定秒数生效。

## 创建防重与回源预算

SHA-256(gid + NUL + URL) 在 `t_link_dedup` 中预约规范短码，跨进程重启仍能复用；并发短码碰撞由主表唯一索引和最多 5 次创建重试处理。`forceNew=true` 绕过规范短码预约。

单库列表直接使用主表 gid 索引，不再维护 `t_link_goto`。没有 Bloom 重建前置条件；格式校验、空值缓存和 DB 预算负责无效查询保护。

Redis 故障时创建/跳转切换为每实例全局兜底限流，默认 10/1000 QPS；查询与限流共用 2 秒 Redis 故障冷却。所有跳转回源（含锁竞争失败与缓存关闭的压测阶段）受每实例 200 QPS、8 并发预算保护，超额或数据库异常返回 503 + `Retry-After: 1`。集群总预算随实例数量增加，需要依据实际负载调整。

## 访问趋势统计

请求线程只做有界内存入队，默认 4096 条（包含发送中事件）。后台使用 RabbitMQ 持久消息、mandatory 和 correlated confirm；NACK/退回/超时在进程存活期间重投同一 payload、保留 msgId。队列满、关闭、崩溃允许丢失，且崩溃损失不能反映在 `dropped` 中。

Lua 原子处理有限时去重、PV、UV/UIP 与 dirty 标记。计数/HLL 保留 2 天，msgId 去重保留 7 天；长期 Redis 故障、数据丢失或超出去重窗口的重放不保证无损、无限期去重。

批次 claim 后保留待处理 payload，数据库回执与统计增量同事务；重试同一批次不会重复累加。click_num 幂等回写完成后才确认批次。回执清理需覆盖可能重试的批次，不能任意删除。

Compose 中 MySQL 使用 commit=1，Redis 使用 AOF everysec/noeviction，RabbitMQ 与 Redis 有数据卷。统计仍不是计费或审计账本，也不承诺全链路 exactly-once。

无密码 Redis 的空字符串配置会在 Redisson 4 全局配置中归一为 null，避免发送空 AUTH；非空密码（包括空格）保持原样。此处理使用 starter 的配置扩展点，不修改 Redis 服务本身。

## 运维与验证

`/admin/stats` 仅包含本地缓存和统计字段，统计明确标注 `deliveryMode=best-effort`。保留缓存清理、过期清理和风控名单更新；Bloom 重建和 gid 索引失败消息接口已经移除。

`ops/alerts.yml` 监控近期统计丢弃、持续积压、死信、broker 不可达和缓存失效待办。配置文件不包含外部通知目的地，推送通知需要另外配置 Alertmanager。

升级现有单库先备份；按需要执行 `sql/reliability_single.sql`、`sql/simplification_single.sql`。旧磁盘 outbox 应在旧版本排空后再切换；新版本不再读取 `SHORTLINK_STATS_SPOOL`。分片库需要独立合并、核验和切换，不能只改驱动地址。

```powershell
mvn clean test

# 可选：临时 Redis，随机端口/密码，不连接开发 Redis
$env:SHORTLINK_TEST_REDIS_EXECUTABLE = (Get-Command redis-server).Source
mvn test

# 真实 HTTP + 独立 MySQL/Redis/RabbitMQ 容器
$env:SHORTLINK_INTEGRATION_TESTS = "true"
mvn -Dtest=FullWorkflowIT test
```

默认单测不需要开发中间件。原有开发环境上下文测试仅在 `SHORTLINK_LOCAL_INTEGRATION_TESTS=true` 时启用；不要将它当作隔离验收。旧吞吐和故障演练数字只属于历史版本，当前版本需重新验证。
