# 安全与可靠性升级

本次升级增加 API 身份与归属校验、可信代理解析、持久化创建幂等索引、数据库回源保护和持久统计补投。旧版本的吞吐与故障演练数字不代表本版本，尤其是统计 fsync、Redis AOF always 与 MySQL durable commit 开启后。

## 升级顺序

1. 备份数据库；暂停创建接口和旧版统计消费/落库任务。先让旧统计任务排空增量，避免新旧任务同时使用同一组 Redis key。
2. 单库执行 `sql/reliability_single.sql`；2 库 × 8 表执行 `sql/reliability_sharded.sql`。脚本新增 `t_link_dedup` 和 `t_stats_flush_batch`，并为历史未删除短链回填规范短码。已有预约不会被覆盖，其他历史短码仍然有效。分片脚本需在维护窗口使用具备 CREATE ROUTINE 权限的账户执行，辅助函数结束时删除。
3. 部署对应的 `sharding.yaml` 规则。当前机器的 `sharding-local.yaml` 也已补齐新索引规则；该私有文件不被 Git 跟踪，部署其他机器时需同步规则。
4. 配置下述 Token、可信代理和统计持久目录，再启动新版本。
5. 验证无凭据 401、跨 gid 403、他人短码修改 404、正常创建/跳转、并发同 URL 同码、`forceNew=true` 新码、MQ 重启后补投。

代码与脚本已准备，现有数据库未执行迁移，现有服务未重启。

## 身份与凭据

必须设置两个独立的随机 Token，均至少 32 字符，无默认密码：

```powershell
$env:SHORTLINK_ADMIN_TOKEN = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:SHORTLINK_API_TOKEN = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

示例仅在当前终端生成凭据；部署时由密钥管理系统持久注入，重启时不要重新随机生成。缺失或过短会让应用启动失败。

- `/api/**` 要求 `Authorization: Bearer <API Token>`。默认配置将该 Token 绑定到 `gid=default`。创建和列表里的 gid 必须与认证身份相同。
- 启用/禁用 SQL 同时校验 `short_uri` 与认证 gid，不存在与不属于本人统一返回 404。普通用户不能解除风控封禁。
- `/admin/**` 仅接受 `X-Admin-Token`，不再接受 URL 中的 `token` 参数。
- 公开短码跳转无需 Token。公网使用 HTTPS。

需要多个 gid 时，在部署私有配置中设置 `shortlink.auth.tokens`，键为 gid，值为对应的独立 Token，不得共用 Token。本版本是服务端配置的 API 凭据体系，不提供注册/登录/用户管理界面。

```yaml
shortlink:
  auth:
    tokens:
      default: ${SHORTLINK_API_TOKEN}
      marketing: ${SHORTLINK_MARKETING_TOKEN}
```

## 代理 IP

默认完全忽略客户端提供的 `X-Forwarded-For`。只有 socket 对端命中 `SHORTLINK_TRUSTED_PROXIES` 中的 IP/CIDR，才从 XFF 右侧向左剥离可信代理，首个不可信地址是客户端。支持 IPv4/IPv6，不解析域名。

例如同机反向代理可配置 `127.0.0.1/32,::1/128`；必须按实际入口填写，避免设置 `0.0.0.0/0`。网关必须覆盖或正确追加 XFF，且阻止客户端绕过网关直连。`server.forward-headers-strategy=none` 用于保留原始 socket 对端地址，不要再安装会提前改写 remoteAddr 的转发头过滤器。

## 创建幂等与故障恢复

`SHA-256(gid + NUL + originUrl)` 作为独立索引的分片键，数据库主键仲裁并发预约。创建查询为“单分片预约 → 按 short_uri 单分片回查”；不再按 gid/url_hash 广播主表。预约先独立提交，主记录和原有本地消息仍同库事务写入；事务前崩溃时后续请求会继续使用预约短码。

`forceNew=true` 绕过规范索引；新建的渠道短码不会替换规范短码。历史 URL 的规范短码由迁移脚本选定。该索引不依赖 Redis TTL，因此 Redis 清空不会遗失幂等映射。不要单独清空预约表。

## Redis 故障保护

短链正向缓存现在携带 `validEnd` 截止时间。即使命中 Caffeine 或 Redis，到期时也返回 410；Redis 的实际 TTL 不超过业务有效期。旧版只包含 URL 的缓存会被视为不可信并回源校验。每天的清理任务也改用统一失效方法，删除正确的缓存键。创建接口拒绝非法 `validType`，以及自定义有效期缺失/已过期的 `validEnd`；API 时间按 Asia/Shanghai 解释，并与 MySQL `DATETIME` 一样精确到秒。

启用、禁用时，状态更新和 `LINK_CACHE_INVALIDATE` 本地消息在同一个数据库事务提交。即时 Redis 失效失败时，任务每 5 秒重试，恢复后删除旧 L2 值并广播到各实例。Redis 正常但 Pub/Sub 丢消息时，L1 最多再保留 5 秒；Redis 自身故障时不能承诺固定 5 秒。监控 `shortlink_messages_cache_invalidation_pending` 的持续积压。

状态变更时 Redis 同时推进短码缓存版本并删除旧值；回源写缓存必须在 Lua 脚本中核对其开始时读到的版本。这阻止了“旧 DB 查询结果在禁用清缓存之后才回写 Redis”的竞态。新版本读取旧版纯 URL 缓存时会回源校验，因此上线时无需批量删除旧缓存。

布隆过滤器只有完成全量重建后才具备“确定不存在”的判定资格。如果 Redis 数据丢失或新部署没有重建标记，跳转会跳过 Bloom 的负判定，继续访问空值缓存/数据库；因此不会把历史短码误判成 410，但 DB 回源压力会升高。观察 `/admin/stats` 的 `bloomTrusted` 和 `shortlink_bloom_trusted`；迁移或 Redis 数据恢复后及时调用受管理员 Token 保护的 `/admin/bloom/rebuild`。在重建完成前应按 DB 承载能力限制流量。

故障时限流切换为每实例全局令牌桶，创建 10 QPS、跳转 1000 QPS，耗尽返回 429。不同随机 IP/短码共享兜底预算。查询与限流共用 2 秒故障冷却时间。

所有跳转数据库回源（包括锁竞争失败、压测关闭 L2）受每实例 200 QPS、8 并发限制，超额立即返回 503 + `Retry-After: 1`。数据库异常同样返回 503，不会被误当作 Redis 异常重复查库。L1 热点和降级时的空结果继续缓存在本地。

配置位于 `shortlink.fallback`。应按连接池、节点数和压测结果调整：总回源预算是实例数乘以单实例预算；各实例配置不是集群总量限制。

## 统计持久化与告警

`t_link_goto` 补偿五次失败的消息会进入 `status=2`。管理员通过 `GET /admin/messages/failed` 查看，确认故障已排除后调用 `POST /admin/messages/{shortUri}/{id}/retry` 重置单条消息。重试 SQL 会同时校验消息 ID、分片键、业务类型和失败状态。`shortlink_messages_goto_failed` 非零触发告警。

请求线程将事件写入本地文件、fsync 后原子提交，后台使用持久消息 + mandatory + correlated publisher confirm 投递。ACK 且未被退回后才删文件；NACK、退回、超时、进程重启都保留并重试同一 msgId。确认接口按 Context7 提供的 Spring AMQP 官方文档核对。

- `SHORTLINK_STATS_SPOOL` 默认为 `./data/stats-outbox`，必须位于持久卷；每实例独占目录，文件锁阻止误共享。保留目录才可在重建容器后补投。
- `shortlink.stats.max-pending` 默认 100000，包含正在等待确认的事件；每个载荷最大 16KB。目录需要足够磁盘空间。磁盘不可写、损坏或容量耗尽时仍优先保证跳转，计入 dropped 并输出限频错误日志；没有“任何故障绝不丢数据”的保证。
- 消费去重和 Redis 聚合改为同一个 Lua 脚本。去重保留 7 天；超过此窗口的人工重放需先对账。当前 Lua 使用同一单机 Redis 的多个 key，不兼容 Redis Cluster 跨槽执行。
- 落库先原子认领持久待处理批次，MySQL 回执和统计增量同事务提交，再幂等刷新 click_num。中断后复用原批次，不重复累计 PV；迟到 worker 不能删除后续新批次。
- `t_stats_flush_batch` 是去重回执，不能在还有同批次待处理任务时删除。回执清理需另行制定保留策略。
- Compose 已加入 Redis/RabbitMQ 数据卷，Redis 使用 AOF always/noeviction，MySQL 使用 commit=1。持久化依赖真实磁盘；非 Compose 环境也应显式配置相同语义。Redis 统计计数/HLL 仍有 2 天 TTL，落库持续故障必须在此期限内处理。
- Prometheus 已加载 `ops/alerts.yml`，覆盖 dropped、持续积压、死信和 broker 不可达。告警能在 Prometheus 中触发；外部通知需要配置 Alertmanager 接收地址，仓库没有实际通知目标。

统计可靠性增加了请求磁盘写入和 broker 确认等待，需重新验证 P99 与吞吐；不要复用旧版只入内存队列的性能数字。

## 测试

需要 Docker 的完整验收可单独运行 `FullWorkflowIT`：它启动独立的 MySQL、Redis、RabbitMQ 容器，执行创建、跳转、过期、禁用恢复、Redis 故障补偿、统计落库及管理员重试。不会连接本机开发数据库。

```powershell
$env:SHORTLINK_INTEGRATION_TESTS = "true"
mvn -Dtest=FullWorkflowIT test
```

无 Docker 时该测试不会作为默认单测运行；正常 `mvn test` 仍运行单测、Web MVC 测试及可选临时 Redis 测试。

```powershell
# 不启动业务中间件：单测、MVC 切片与文件重启恢复测试
mvn test

# 额外启动随机端口、随机密码的临时 Redis，测试 Lua 与批次重试；结束自动关闭
$env:SHORTLINK_TEST_REDIS_EXECUTABLE = (Get-Command redis-server).Source
mvn test
```

原有连接开发环境的上下文测试仅在 `SHORTLINK_LOCAL_INTEGRATION_TESTS=true` 时启用，避免它与独立容器验收同时运行。默认测试不会连接开发中间件。

Compose 新环境的应用连接凭据需与 Compose 一致，例如使用环境变量 `SPRING_DATASOURCE_PASSWORD=root123`、`SPRING_DATA_REDIS_PASSWORD` 为空、`SPRING_RABBITMQ_USERNAME=admin`、`SPRING_RABBITMQ_PASSWORD=admin123`。生产环境应替换这些本地示例密码。
