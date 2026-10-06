# 实习项目主版本精简

## 当前范围

主版本使用 Vue 工作台、Spring Boot、单库 MySQL、Caffeine/Redis 多级缓存和 RabbitMQ 访问趋势统计。保留创建防重、归属鉴权、有效期、缓存版本栅栏、限流、受限回源、消费幂等及统计批次回执。

| 精简项 | 当前实现 |
|---|---|
| 分库分表与 gid 异构索引 | 列表直接按 `gid` 查 `t_link`，创建后立即可读，不再写 `LINK_GOTO_SYNC` 消息 |
| 自研发号器 | `Link` 与缓存失效待办统一使用 MyBatis-Plus `ASSIGN_ID`；不再查询号段表 |
| Bloom | 随机短码由唯一索引与有界碰撞重试保障唯一；无效查询由格式校验、空值缓存和回源预算控制 |
| 请求磁盘 outbox | 有界内存队列，后台 mandatory + correlated confirm，未确认重试保留原 `msgId` |
| 实验维护入口 | 移除 Bloom 重建、gid 失败消息列表与重试接口、号段/Bloom 指标及前端同步轮询 |
| 冗余运行依赖 | 移除 ShardingSphere 模块及未使用的分页插件/parser；实验源码移入 `experiments/` |

Redis 聚合和数据库批次回执没有删除：前者展示原子消费和批量落库，后者保证同一批增量重试不会重复累加。缓存失效待办也保留，避免禁用成功但 Redis 故障导致旧缓存长期有效。

## 统计契约

- 统计仅用于访问趋势，不用于计费、财务或审计。PV 是接受并最终处理的访问事件数；UV/UIP 由 HyperLogLog 估计。
- 请求线程不做磁盘写入和 MQ 网络等待。默认最多保留 4096 个待确认事件，包含正在发送的事件；满时丢弃新事件并更新 `dropped`。
- NACK、退回、超时及临时 MQ 故障在进程存活期间重试原始事件。重投保留 `msgId`，Lua 的有限时去重避免重复消费；不是无限期 exactly-once。
- 正常关闭会显式丢弃未确认的内存事件；进程崩溃无法记录丢失数量。`dropped=0` 不代表全链路无损。
- Redis 计数/HLL 有 2 天 TTL，去重键保留 7 天；长期故障、Redis 数据丢失仍可能影响统计。Compose 使用 AOF everysec/noeviction，不能承诺零丢失。
- 数据库落库回执与增量同事务，点击数幂等回写；回执不能在仍可能重试的批次存在时清除。

## 部署与存量数据

代码改动不执行数据库迁移，不删除旧表、旧分片、旧 outbox 文件或业务记录。

### 已有单库

1. 先用旧版本排空磁盘 outbox，并让旧统计消费与落库任务完成；不要让新旧版本同时消费同一 Redis 统计键。
2. 若已完成之前的安全升级，可直接沿用五张核心表。否则先备份，在维护窗口执行 `sql/reliability_single.sql`，补齐防重预约与批次回执。
3. 可执行 `sql/simplification_single.sql` 补齐 `(gid, del_flag, id)` 列表索引。脚本可重复执行，但大表建索引仍应安排维护窗口。
4. 停止旧版本后重新构建并启动新版本；前端和后端必须一并更新，旧运维接口现在返回 404。

旧 `t_link_goto`、`t_id_segment` 和 `LINK_GOTO_SYNC` 消息不再被读取或处理，留存作历史数据，不自动清理。`url_hash` 和消息中的 `shard_key` 列名保留兼容，避免强制破坏性改表；实际创建防重使用 SHA-256 预约键。

### 已有分片数据库

主版本不再提供 ShardingSphere 驱动，不能只改 URL 就把分片数据当作单库。备份后在独立目标库导入 `sql/schema.sql`，合并所有 `t_link_*` 的原始记录、去重预约和统计相关数据，并核对短码、记录数量、有效期、状态、点击数和规范短码。迁移中应保留原始主键、短码及预约，不覆盖已有规范短码。

切换前验证目标库的创建、列表、跳转、禁用恢复、过期和统计。迁移完成后才切换数据源；旧分片和备份仍保留。本次没有生成或执行自动合并脚本，因为实际数据源、冲突处理和切换时机需要部署者确认。

本机未跟踪的 `application-local.yaml` 已改为 MySQL 单库连接，凭据和其他本机设置未改动。`SHORTLINK_DB_URL` 可指定已准备好的目标单库；其他机器请用对应私有配置或 `SPRING_DATASOURCE_URL` 覆盖。旧私有 `sharding-local.yaml` 保留，但新版本不再加载。

`SHORTLINK_STATS_SPOOL` 不再生效。遗留 outbox 不会被新版本重放；切换前必须排空，无法排空时先保留旧版本和持久目录，不要直接删除事件文件。

## 验证

```powershell
mvn clean test
npm --prefix frontend run test
npm --prefix frontend run build:backend
npm --prefix frontend run test:e2e
mvn package -DskipTests
```

真实 HTTP/MySQL/Redis/RabbitMQ 验收使用独立容器：

```powershell
$env:SHORTLINK_INTEGRATION_TESTS = "true"
mvn -Dtest=FullWorkflowIT test
```

容器验收不连接开发数据库。默认单测不代表已完成真实数据库验收；`experiments/` 内的历史测试也不属于本次测试结果。历史压测数据仅用于展示原实验，当前版本必须重新压测。

## 本次验证（2026-10-07）

- 后端单测、MVC 与独占临时 Redis 验证：86 项通过，1 项开发环境上下文测试按设计跳过。
- 前端：13 项 Vitest 通过，TypeScript/生产构建通过，6 项 Playwright 通过（含四断点、剪贴板、二维码和管理员请求契约）。
- Maven 打包通过；分发 JAR 排除本机 `application-local.yaml`、`sharding-local.yaml`，本地运行仍可读取这些私有资源。
- 实际 JAR 使用独立临时无密码 Redis 启动，`/app/`、`/admin/stats`、受保护的 `/v3/api-docs` 返回 200，无凭据 API 返回 401。该装配检查禁用统计投递和 MQ 消费，并将 DB/MQ 指向不使用的本机端口，不等于真实数据库与 MQ 业务验收。
- 本机未检测到 Docker，`FullWorkflowIT` 已同步修改并编译，但未运行完整容器验收；CI 已配置该步骤，本次没有触发远程 CI。
- 未迁移、删除业务数据库或旧 outbox 数据，也未重启原有后端服务。
