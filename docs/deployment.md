# 共享小内存服务器部署

本项目在本机构建 Vue 和 JAR，服务器只负责运行。不要在共享 2 核主机上运行开发用 `docker-compose.yml`：它会新增 MySQL、占用 3306，并设置 1 GiB InnoDB 缓冲池。

## 隔离边界

本次服务器上已经运行 IssueFlow。保留其 8080、数据库、账号、服务配置、发布目录、定时任务和 `mayiqin.top` 站点；不调整其 JVM 或 MySQL 全局配置。

| 本项目组件 | 监听 / 数据 | 内存硬上限 | CPU 上限 |
| --- | --- | --- | --- |
| Spring Boot + Vue | `127.0.0.1:8081`，`/opt/url-shortener` | 384 MiB，堆 192 MiB | 60% 单核 |
| Redis | `127.0.0.1:6381`，`/var/lib/url-shortener-redis` | 64 MiB，数据集 16 MiB | 10% 单核 |
| RabbitMQ | AMQP `127.0.0.1:5673`，`/var/lib/url-shortener-rabbitmq` | 192 MiB，水位 144 MiB | 25% 单核 |
| MySQL | 复用现有 `127.0.0.1:3306`，独立 `url_shortener` 库及账号 | 不修改原实例配置 | Hikari 最多 3 连接 |
| Nginx | 独立 `s.mayiqin.top` 站点，共享 80/443 | 复用现有进程 | 不修改原站点 |

CPUQuota 的百分比以一个逻辑 CPU 为基准，不是整台 2 核机器的百分比。新服务内存硬上限合计 640 MiB，不代表会预先占满，也不代表整机不会有内存压力。MySQL 仍是共享资源，账号最多允许 4 个连接，应用池最多 3 个；不要在这台机器上压测。

Redis 使用 AOF everysec、`noeviction`，避免淘汰统计幂等键和缓存栅栏；达到数据集上限时会拒绝写入，需要观察容量并处理。统计是有损趋势数据，不是计费/审计流水。RabbitMQ 不启用管理插件；其分布端口 25673、EPMD 43691 也只绑定回环地址。

## 首次安装

先确认 SSH 主机、系统资源、原站点健康、所有目标目录/端口空闲及 MySQL root 本地管理连接可用。安装软件和新增站点前取得服务器所有者确认。

1. 本机执行 `npm --prefix frontend ci`、`npm --prefix frontend run test`、`npm --prefix frontend run build:backend`、`npm --prefix frontend run test:e2e`、`mvn -B package`。
2. 检查 JAR 包含 `static/app/index.html`、`application-small.yaml`，且不包含本机 `application-local.yaml`、`sharding-local.yaml`。
3. 将 JAR、`deployment/`、`sql/schema.sql` 上传到新的 `/opt/url-shortener/releases/<release>` 目录，核对 SHA-256。部署对应当前工作区构建结果，不应假定未提交的改动已属于 HEAD。
4. 首次安装 Redis、RabbitMQ 前，确认服务器没有其他使用者。禁止默认包服务自动监听公网：

   ```sh
   systemctl mask redis-server.service rabbitmq-server.service epmd.service epmd.socket
   DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l apt-get install --no-install-recommends -y redis-server rabbitmq-server
   ```

   不运行全机 upgrade，不自动重启既有服务。本项目通过 `url-shortener-redis` 和 `url-shortener-rabbitmq` 独立服务运行；默认包服务保持 masked。若已有上述服务，停止安装并人工规划，不可直接屏蔽它们。

5. 在发行目录执行：

   ```sh
   python3 deployment/bootstrap.py --domain s.mayiqin.top
   python3 deployment/activate-release.py /opt/url-shortener/releases/<release>
   ```

   `bootstrap.py` 仅用于首次部署，发现已有同名配置、库、账号或端口即停止，不覆盖、不重置数据。中途失败需要先检查已创建资源，不能删除目录后盲目重跑。它生成五个独立随机凭据；MySQL 应用账号仅有本库 SELECT/INSERT/UPDATE/DELETE 权限。DDL 由本地 root 完成。

配置位于 `/etc/url-shortener/app.env`，前端所需 Token 单独位于 `/etc/url-shortener/access.env`，均为 root 所有、0600。不要把文件内容复制到聊天、日志、截图或 Git。前端「连接设置」的服务地址留空，gid 为 `default`，分别使用 API Token 和管理员 Token；前端产物不注入凭据。

## HTTPS 和 DNS

将 `s.mayiqin.top` 的 A 记录配置为当前服务器公网 IP；如果设置了 AAAA，也必须正确指向可访问的本机 IPv6。先从服务器确认 DNS 解析，再申请本项目独立证书：

```sh
certbot certonly --non-interactive --webroot -w /var/www/url-shortener-acme \
  --cert-name s.mayiqin.top -d s.mayiqin.top --keep-until-expiring
python3 deployment/enable-https.py --domain s.mayiqin.top
```

复用已经注册的 Certbot 账号，但不更改原域名的证书/续期配置。首次配置的 HTTP 站点仅提供 ACME 验证，其他请求返回 503，防止明文传输凭据。HTTPS 成功后访问 `https://s.mayiqin.top/app/`，HTTP 自动跳转 HTTPS。续期 hook 仅在本域名证书更新时校验并 reload Nginx。

DNS 未生效时，可以 `ssh -L 18081:127.0.0.1:8081 aliyun`，通过 `http://127.0.0.1:18081/app/` 验证页面与同源 API；生成的短链接仍使用正式 HTTPS 域名，DNS 和证书就绪前不能声称公网可用。

## 更新与回滚

每次本机测试、构建后上传新 release，核对散列，再执行该 release 中的 `activate-release.py`。脚本原子切换 `current`，最多等待约 180 秒检查 `/actuator/health`，失败会停止新进程并恢复前一个 release。首次启动失败则保持本项目停止，原站点不受切换脚本操作。

手动回滚时对之前已验证的 release 再执行同一脚本。切换脚本不运行 SQL 迁移；将来变更数据库结构必须单独设计兼容迁移和备份。脚本不会删除旧 release 或历史数据。

```sh
systemctl status url-shortener url-shortener-redis url-shortener-rabbitmq
journalctl -u url-shortener -n 100 --no-pager
systemctl show url-shortener -p MemoryCurrent -p MemoryHigh -p MemoryMax -p NRestarts
free -m
curl -fsS http://127.0.0.1:8081/actuator/health
```

Nginx 配置只有校验成功才 reload，不 restart。代理覆盖客户端伪造的转发头，应用只信任回环代理；数据库与中间件端口无需放行到公网。访问日志沿用系统现有 Nginx logrotate 规则。小内存 profile 默认不公开 Swagger，接口说明以 README 为准。现阶段只有本次部署的本库初始快照，没有配置本项目定时备份、异地备份或外部告警；原 IssueFlow 的备份任务不自动覆盖本库，不能当作具备完整容灾能力的生产部署。

Actuator 没有统一套用管理员拦截器，不能把其指标端点直接反代到公网。本站点拒绝 `/actuator`、`/actuator/` 及矩阵参数路径；健康检查和 Prometheus 仅经本机/SSH 隧道访问，工作台运行观察仍通过受管理员 Token 保护的 `/admin/stats` 使用。

业务验收可在服务器上以 root 执行 `python3 /opt/url-shortener/current/deployment/smoke-test.py`，它会创建一条标记为 `deployment smoke test` 的短链，验证幂等、归属、跳转、禁用恢复、风控和真实统计落库，最后停用测试链接。它不是压测，不应被当作持续探活任务反复执行。
