# 短链接管理项目

Spring Boot 4.0.7 / Java 21 / MyBatis-Plus / MySQL / Redis / Caffeine / RabbitMQ / Vue 3。

面向实习项目的可运行主版本：创建、分享、跳转、启用/停用、过期管理和访问趋势统计。核心亮点聚焦缓存一致性、异步统计与故障保护，不默认引入分库分表或自研基础设施。

## 当前架构

```text
创建：认证与归属校验 → 限流 → URL 校验 → 持久规范短码预约 → 主表 INSERT → 缓存预热
列表：认证与归属校验 → 按 gid 直接读取主表（最多 100 条，创建后立即可读）
跳转：限流 → 格式校验 → Caffeine → Redis → 空值缓存 → 锁/双重检查 → 受限 DB 回源 → 302
统计：有界内存队列 → 后台 MQ 确认/重试 → Lua 幂等聚合 → 数据库批次回执与增量同事务
管理：状态与缓存失效意图同事务 → 原子失效/广播 → Redis 故障后由待办重试
```

随机六位短码由数据库唯一索引兜底；主键采用 MyBatis-Plus 内置策略。多级缓存命中时仍检查有效期，回写时使用版本栅栏。

统计用于趋势展示，队列满或进程崩溃允许丢失事件，不保证全链路无损，不适用于计费或审计。默认容量 4096，包含正在等待 MQ 确认的事件；监控展示积压、明确丢弃和投递失败次数。

## 启动

先在单库导入 `sql/schema.sql`。已有单库或分片数据请先阅读 [精简与切换说明](docs/simplification.md)，不要直接覆盖、删除旧数据库。

必须通过环境变量提供两个独立随机 Token（均至少 32 字符）：`SHORTLINK_API_TOKEN`、`SHORTLINK_ADMIN_TOKEN`。默认 API Token 绑定 `gid=default`，前端构建不注入 Token。

本地已有中间件时，在未跟踪的 `application-local.yaml` 中配置 MySQL、Redis 和 RabbitMQ：

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
npm --prefix frontend ci
npm --prefix frontend run dev
```

浏览器打开 `http://127.0.0.1:5173/app/`，默认进入明确标注的演示空间。在「连接设置」填入 gid/API Token 后连接真实服务；“服务地址”留空使用 Vite 代理到本机 8080。管理员 Token 只用于运行观察和维护。凭据仅保存在页面内存。

也可先 `docker compose up -d` 启动三种中间件。新 MySQL 卷自动执行单库初始化脚本；已有卷不会自动更新。应用连接参数需与 Compose 一致，通过 `SPRING_DATASOURCE_*`、`SPRING_DATA_REDIS_*`、`SPRING_RABBITMQ_*` 配置，不能直接沿用其他本机配置的示例密码。

让后端同时提供页面：

```powershell
npm --prefix frontend run build:backend
mvn package -DskipTests
java -jar target/URL_Shortener-0.0.1-SNAPSHOT.jar
```

访问 `/app/`；Dockerfile 也会构建并打包前端。直连其他后端源时配置 `SHORTLINK_CORS_ORIGINS` 后重启，正式请求仍需 Token。详情见 [前端说明](frontend/README.md)。

本机私有配置不会进入分发 JAR。运行 JAR 需要私有配置时，将文件保存在外部目录并使用 `--spring.config.additional-location=file:./config/` 和 `--spring.profiles.active=local`，或通过环境变量配置连接。

## 接口

| 接口 | 用途 |
|---|---|
| `POST /api/link` | 创建；同 gid/URL 默认复用规范短码，`forceNew=true` 强制新码 |
| `GET /api/link/list?gid=default&limit=100` | 从主表读取当前身份的链接 |
| `GET /{6位短码}` | 有效时 302，不存在/禁用/过期时 410 |
| `POST /api/link/{code}/disable`、`enable` | 校验归属与风控状态后停用/恢复 |
| `GET /admin/stats` | 本地缓存、统计内存队列及落库水位 |
| `POST /admin/cache/local/clear` | 清当前实例的本地缓存 |
| `POST /admin/expired/clean` | 逻辑删除过期链接并失效缓存；单次有时间预算，返回 `status`/`executed`/`cleanedRows`/`moreRemaining` 区分跑完、提前结束与未抢到锁 |
| `POST /admin/risk/reload` | 选择性更新域名黑白名单 |
| `GET /actuator/prometheus` | 受管理员凭据保护的监控指标 |

API 使用 `Authorization: Bearer <token>`；管理接口使用 `X-Admin-Token`。禁用后其他实例的 L1 由广播失效，漏收时最多保留本地 TTL；Redis 故障期间的 L2 清理由持久待办恢复后补偿，不能承诺固定秒数。

## 验证与文档

```powershell
mvn clean test
npm --prefix frontend run test
npm --prefix frontend run build
npm --prefix frontend run test:e2e
```

真实 MySQL/Redis/RabbitMQ 的独立容器验收：设置 `SHORTLINK_INTEGRATION_TESTS=true`，再执行 `mvn -Dtest=FullWorkflowIT test`，需要可用 Docker。默认单测不等于真实中间件验收。

- [项目结构树](docs/项目结构树.md)：当前主版本目录与职责。
- [安全与可靠性说明](docs/security-reliability-upgrade.md)：鉴权、缓存与统计边界。
- [精简与切换说明](docs/simplification.md)：本次取舍、旧表兼容和部署检查。
- [历史实验](experiments/README.md)：分片、号段、数值编码，均不参与默认构建。
- [历史 ADR](docs/adr.md)、[历史压测](benchmark/results/baseline.md)：保留原实验记录，不作为当前版本性能或无损承诺。
