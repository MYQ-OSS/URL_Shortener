# 阿里云部署验收记录

验收日期：2026-10-15（Asia/Shanghai）。以下是本次实机观察，不是压测或容量承诺。

## 部署结果

- 工作台：`https://s.mayiqin.top/app/`；HTTP 自动 301 到 HTTPS。
- 服务器：Ubuntu 24.04，2 个逻辑 CPU，系统可见内存 1613 MiB，既有交换空间 2047 MiB。
- 当前发布：`/opt/url-shortener/releases/url-shortener-20261015-2134-aeb8313`；`current` 指向该目录。
- 构建基础 HEAD：`aeb8313`，包含部署时尚未提交的部署/HTTPS 改动及用户原有 `RedirectController` 改动，并非纯 HEAD 构建。
- JAR SHA-256：`ee06102665bd6900410af4b98c7058240c20e55e5d40287717484956354bdd09`。
- 使用 `small` profile，Vue 随 JAR 提供；未安装 Docker、未在服务器运行 Maven/npm，未部署 Prometheus/Grafana。
- 本记录对应部署验收时的工作区快照；后续 Git 提交不改变线上 JAR 及其 SHA-256，用户原有控制器改动单独保留。

## 资源与原项目保护

应用/Redis/RabbitMQ 分别设置 384/64/192 MiB 内存硬上限，CPU 分别最多使用单核的 60%/10%/25%。Java 堆 192 MiB、Hikari 池 3 连接、Redisson 工作线程和 Netty 线程各 2，L1 缓存 2000 条、统计内存缓冲 512。资源配置按 Context7 查得的 Redisson/RabbitMQ 官方配置定义核对，并以实际启动、业务与重启测试验证。

最后一次采样中，应用 cgroup 约 308 MiB、Redis 约 3 MiB、RabbitMQ 约 86 MiB，整机 available 约 400 MiB，交换空间使用 0。三个新服务均无自动重启，cgroup `memory.events` 的 `max`、`oom`、`oom_kill` 均为 0。可用内存会随文件缓存、业务和原项目任务变化，不适合在该共享主机上压测。

IssueFlow 保护检查：

- `https://mayiqin.top/` 在部署前、部署中和最终验收均返回 200。
- Java PID 一直为 243075；MySQL 主进程 236919、Nginx master 236407 未重启。Nginx 仅进行了经配置校验后的 reload。
- 原 `/etc/issueflow/issueflow.env`、IssueFlow Nginx 站点、IssueFlow systemd 单元的 SHA-256 前后一致。
- 原 release、8080、数据库账号、服务资源限额和现有定时任务保持不变。
- 复用 MySQL 进程但使用独立 `url_shortener` 库和账号；实测该账号可访问本库五张表，而 `USE issueflow` 被拒绝。
- 新应用 8081、Redis 6381、AMQP 5673、Rabbit 分布端口 25673、EPMD 43691 均只监听回环地址，没有新增公网中间件端口。

## 测试

| 检查 | 结果 |
| --- | --- |
| `mvn -B package` | 97 项：91 通过、6 跳过，0 失败、0 错误 |
| Vue 单元测试 | 14 通过 |
| 原有 Playwright 浏览器回归 | 6 通过 |
| 分发 JAR 检查 | 包含 Vue 与 small profile；不含本机私有 YAML |
| 服务器真实业务 smoke | 创建、HTTPS 短链、幂等复用、列表、跨 gid 拒绝、302、禁用 410、恢复、内网 URL 拦截通过 |
| 异步统计 | 实际经过 RabbitMQ、Redis 和定时任务落库，日汇总 PV=2；不把小时行再次相加冒充总点击 |
| SSH 隧道浏览器 | 真实打包 Vue 连接云端 API/管理员接口，未拦截/模拟请求，返回 200 |
| 正式 HTTPS 浏览器 | TLS 正常；真实同源连接、创建、列表、停用和运行观察通过；无 JS 错误或 localStorage 凭据 |
| 公网跳转与安全 | HTTPS 短链 302 到正确目标，停用后 410；Actuator 普通/矩阵参数/编码路径均返回 404 |
| 重启与持久性 | Redis/RabbitMQ 独立重启后应用恢复 UP，原短链与统计仍在，原项目未重启 |
| 独立证书 | `s.mayiqin.top`，到期 2027-01-13；Certbot timer 已启用 |
| 续期演练 | 首次 staging 外网超时；重试 `--dry-run --run-deploy-hooks` 成功，hook 验证并 reload Nginx |

6 个跳过项为需要本地中间件/显式环境开关的测试；本机没有 Docker，未执行 Testcontainers 完整套件。云端 smoke 验证真实运行链路，但不等同于覆盖完整故障矩阵。所有验收链接都已停用并明确标注测试用途。

## 凭据、备份与运维边界

- 使用新生成的独立随机 API/Admin Token，不复用聊天中曾出现的值。凭据不进入 Git、JAR、前端构建或本记录。
- `/etc/url-shortener/access.env`：仅包含工作空间与前端所需 Token，root:root 0600。
- `/etc/url-shortener/app.env`：后端连接凭据，root:root 0600。
- Redis/RabbitMQ 配置分别为 root:redis / root:rabbitmq 0640；Rabbit 用户没有管理员标签，只拥有本 vhost 中 `shortlink.*` 资源权限。
- 公网不反代 Actuator，工作台使用受 Token 保护的 `/admin/stats`；本机健康检查仍为 UP。
- 本库初始快照：`/var/backups/url-shortener/initial-20261015-2136.sql.gz`，root-only，并已通过 gzip 完整性检查。尚无本项目定时备份、异地备份或外部告警。
- 更新、回滚和首次安装流程见 [部署说明](deployment.md)。原始基础发行包之后增加了站点安全配置；恢复部署请使用最终发行包或当前仓库模板，不要回退到首次上传的站点模板。
