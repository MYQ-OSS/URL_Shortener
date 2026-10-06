# 折页 FOLD · Vue 链接工作台

Vue 3 + TypeScript + Vite + Pinia + Vue Router + VueUse。图标使用 Lucide，二维码由 qrcode 在浏览器生成。界面使用自定义 CSS，不套通用后台模板。

## 本地启动

```powershell
cd frontend
npm ci
npm run dev
```

打开 `http://127.0.0.1:5173/app/`。默认进入明确标注的演示空间，示例状态仅存在当前页面内存。创建、复制、二维码、搜索筛选、停用恢复和管理维护都可体验。

连接真实服务：先按后端升级说明启动应用，再打开「连接设置」，填写与 API Token 绑定的 gid。管理员 Token 可选，只有运行观察和维护需要它。凭据不写入 localStorage、sessionStorage、URL 或构建文件，页面刷新后清除。

开发时服务地址推荐留空，Vite 将 `/api`、`/admin` 代理到 `http://127.0.0.1:8080`，无需浏览器跨域。可在当前终端设置 `API_PROXY_TARGET` 改变代理目标。

若填写服务地址，浏览器会直接访问该源。后端默认只允许 `http://localhost:5173`、`http://127.0.0.1:5173`，并放行不带 Token 的 CORS 预检；正式请求仍要求 Token。部署其他前端域名时设置 `SHORTLINK_CORS_ORIGINS`（逗号分隔的完整来源）并重启后端。不要把 Token 环境变量写入前端构建环境。

## 接入 Spring Boot

```powershell
# 项目根目录执行
npm --prefix frontend run build:backend
mvn spring-boot:run
```

构建产物复制到 `src/main/resources/static/app/`，访问后端根地址或 `/app/` 即可。前端使用 Hash 路由，刷新页面不需要通配路由；六位短码跳转仍由原控制器处理。构建目录被 Git 忽略，应提交源码和 package-lock.json。

Dockerfile 包含 Node 前端构建阶段，镜像会将页面一并打入 JAR。该阶段不注入任何 Token。.dockerignore 排除了本地私有配置和依赖缓存。

## 页面与接口

| 页面 | 功能 | 后端接口 |
|---|---|---|
| 链接工作台 | 创建、列表、启用/停用、复制、二维码、状态筛选 | `/api/link`、`/api/link/list`、`/api/link/{code}/enable` / `disable` |
| 运行观察 | L1 读数、统计积压/丢弃/投递失败与落库水位 | `/admin/stats` |
| 运行维护 | 本机缓存清理、过期清理、选择性更新黑白名单 | `/admin/cache/local/clear`、`/admin/expired/clean`、`/admin/risk/reload` |
| 连接设置 | gid/API Token 连接、可选管理员 Token、断开/演示切换 | 连接时调用列表接口验证 |

后端列表最多返回 100 条。页面读数只统计已加载链接，不声称是全库总量；当前后端没有单链报表查询接口，因此没有虚构趋势图。创建后直接刷新主表列表，不再轮询索引同步。刷新失败会保留本地创建结果并明确提示，不能把已创建成功误报为失败。

后端 Long 主键使用 `json-bigint` 无损解析为字符串。有效期按北京时间解释，过期和风控封禁条目不会提供误导的“恢复”操作。

统计展示为访问趋势，允许队列满或进程崩溃时丢失事件，不用于计费和审计。运维页只展示主版本实际组件，详情见 [精简说明](../docs/simplification.md)。

## 验证

```powershell
npm run test
npm run build
npm run test:e2e
```

单测覆盖请求头、链接 ID 精度、错误反馈、到期规则和创建后直接刷新与失败处理。Playwright 验证桌面/手机、四个断点、剪贴板、二维码、停用恢复、管理员请求头与维护。Windows 默认使用已安装的 Edge；其他环境准备 Playwright Chromium，或通过 `PLAYWRIGHT_CHANNEL` 选择浏览器。

浏览器测试中真实连接场景使用请求拦截验证 API 契约，并不意味着已运行 MySQL/Redis/RabbitMQ 的整套环境。后端真实验收仍使用项目中的 `FullWorkflowIT`。
