# Java 管理控制台与配置阅读指南

本章对应上游固定版本 `6337fc823ce20251c8de8085948165c81aaa701e` 的 `docs/004-prompts.md`、`005-panel.md`、`006-deployment.md` 与 `src/app/api.ts`。后端使用 JDK 21 `HttpServer`，前端使用浏览器原生 JavaScript。运行控制台不需要 Node.js，也不依赖 CDN。

## 先沿一条请求阅读

1. `management/ManagementServer.java`：将 URL、请求体、同源检查转成应用调用。
2. `management/ManagementViews.java`：将同一份 SQLite / Engine 数据投影成页面；不单独维护“演示状态”。
3. `core/Engine.java`：停止、重试、重评、附加、订阅仍通过领域事务，HTTP 不直接修改 Ticket。
4. `config/VersionedSettings.java`：每块乐观锁、不可变版本、参数验证、策略变更。
5. `config/PromptService.java`：完整的上游提示文件、按角色组装、版本与模板验证。
6. `management/ResourceService.java`：供路由器与 Executor 使用的分页读取和可见范围。
7. `src/main/resources/web/app.js`：六个页面、表单、保留草稿、冲突合并和历史恢复。

## 页面验收矩阵

| 上游能力 | Java 对应实现 | 验证入口 |
| --- | --- | --- |
| Overview：人工注意项、健康、24 小时流量、运行/排队 | `overview()`、`load()`，人数与目标列表共用 Engine 阶段 | `ManagementServerTest.allReadPagesUseDomainRecordsAndCorrectLoad` |
| Source：开关、生命周期、过滤、丢弃消息 | `sources()`，过滤来自 Disposition；丢弃来自真实 Outbox | 来源页及 HTTP `GET sources` |
| Trigger：订阅/评估中/最近/全部过滤 | `threads()`、按时间与 ID 稳定分页 | `GET threads` |
| Thread：消息时间线、投递归属、退回理由、上下文、决定 | `threadDetail()`、`triggerView()` | Thread 详情与较早页 |
| 搜索内容、ID、消息链接、#编号、GitHub URL | `search()`，Source 缓存链接也参与匹配 | `GET search?q=%2312` |
| Eval：理由、策略版本、审计、不可用决定忽略 | `decisions()`、`dismissDecision()` | Eval 页与 dismiss 路由 |
| Execution：暂停、阶段过滤、重试、停止 | `ticketStage()`、`pause()`、Engine 操作 | `ticketRetryAnswerStopAndOwnedFollowupRequeueMutateRealEngine` |
| Ticket：授权、原始消息、输入、Assignment、订阅、Session | `ticketDetail()`、`ResourceService` | Ticket 详情 |
| Sources 设置：账户、监听、白名单、机器人、同步、准入 | `VersionedSettings` + `ManagementHost` 的实际 Source | Sources / Eval 设置 |
| 执行器：角色模型、并发、目录、prepare、阻止命令 | executor 配置块 | Execution 设置 |
| Prompts：三个团队段、15 个内置部分、技能 | 完整 bundled prompts / skills | 提示词编辑器 |
| 完整提示词、与保存/默认内容比较、升级默认漂移 | `preview()`、`defaultChanged()`，旧默认文本持久化 | Full prompt 与 Default updated |
| 历史、恢复、新版本、冲突不覆盖草稿 | 版本追加 + `merge3` + sessionStorage | 两个浏览器页同时编辑测试 |
| Deployment：检查、已验证账户、状态目录、机器限制 | setup 端口 + deployment 配置块 | Deployment 页 |
| 资源权限、分页、当前/曾用提示版本 | `ResourceService.read`、`PromptService.recordUse` | `workerResourcesRejectOtherTicketAndSharedThreadMetadataLeaks` |
| Loopback、Host、Origin、CSRF 请求头、大小限制 | `ManagementServer` | `enforcesOriginCsrfHostAndBoundedBodies` |

在独立 Java 进程里，DeepSeek Harness 自带的 Plugin Manager 被本服务的 Deployment 页面取代；状态目录只能在启动参数中改变。机器限制仍可热更新。账户执行与模型 Session 的实际状态由 `ManagementHost` 接入，不以假成功填充未实现的外部操作。

## HTTP 契约

基础地址：`http://127.0.0.1:<port>/orchestrator/api/`。每个 API 请求必须携带 `x-orchestrator-request: 1`；Host 只接受当前端口的 `127.0.0.1` 或 `localhost`。提供 Origin 时必须等于对应 `http://Host`。服务器本身只监听 IPv4 loopback。不要将其通过公网反向代理暴露。

保留上游路由：

- `GET state`、`overview`、`search?q=`、`sources`、`settings`、`models`
- `GET threads?filter=&offset=&limit=`、`GET threads/<key>?before=&around=&limit=`
- `POST threads/<key>/unsubscribe`、`POST triggers/<id>/requeue`
- `GET decisions?kind=&window=24h&offset=&limit=`、`POST decisions/<id>/dismiss`
- `GET tickets?state=&offset=&limit=`、`GET tickets/<id>`
- `POST tickets/<id>/retry`、`POST tickets/<id>/stop`
- `POST executor/pause`，请求体 `{"paused":true}`
- `PUT settings/<block>`，请求体 `{"value":{...},"revision":n}`
- `GET policy/history`、`POST policy-sync/<source>`、`POST sources/<source>/labels`
- `POST setup/check`
- `GET prompts?revision=&offset=`、`PUT prompts`、`POST prompts/preview`

Java 独立服务补充的操作：

- `GET settings/<block>`、`GET settings/history?block=`、`POST settings/<block>/restore`
- `GET deployment`、`PUT deployment`
- `GET triggers/<id>`、`GET tickets/<id>/transcript`
- `POST tickets/<id>/answer`（本机操作人答复变成可审计 Trigger）
- `POST tickets/<id>/attach`、`POST tickets/<id>/subscribe`
- `GET resources?resource=&offset=&limit=`（管理操作人拥有主会话读取范围）

错误为 JSON：400 参数或领域拒绝；403 非同源/越权；404 不存在；409 版本冲突；500 意外错误。普通 body 最大 128 KiB；提示词 body 上限按可配置字符限制放大到 UTF-8 最坏情况。静态资源设置 CSP、禁止 iframe、no-store 与 nosniff。

## Settings：为什么不是一个大 JSON 开关

Policy、Eval、Executor、每个 Source、Pause、Deployment 各有独立 revision。因此暂停不会使正在编辑的 Executor 草稿失效。历史记录包含保存来源、时间、前一版本、变化字段和值；恢复旧版本是新的保存，不删除历史。

`VersionedSettings.save(..., "tool")` 对模型入口执行额外限制：不能改变账户、凭据环境变量名、API 目标、执行路径、prepare/blocked commands、发布与写入权限、部署限制。HTTP 面板使用可信的 `panel` 来源。来源会员同步使用 `sync` 并在远程读取前捕获旧 revision，防止覆盖同步期间的人工编辑。

Java REST Source 的 `tokenEnvironment` 只保存环境变量名称，不保存令牌。扩展字段 `apiBase`、GitHub `botLogin`、Feishu `botId` / `appId` 用于 Java REST 连接；原版 `cli`、GitHub `pollSeconds` / `failuresBeforeError`、Feishu `readPageSize` / `linkTimeoutMs` 仍保持清晰命名。

## Prompts：与普通字符串拼接的区别

- `src/main/resources/prompts/` 与两个内置 Source skills 直接保留上游文本，许可证随 JAR 包含。
- 组装顺序固定：共享 → 角色 → 团队三段 → subscriptions/replies → dispatch/work → 主会话 admission → handoff → environment → 主会话 policy。
- environment 和 policy 模板保留必要占位符。替换仅执行一遍，变量值中的 `{...}` 不会再次插值。
- 覆盖记录内置文本的哈希。升级后默认变动会被标记，面板显示旧/新默认差异，并提供“保留我的内容”和“使用新默认内容”。
- 每次模型请求重新读取当前配置，`recordUse` 记录该 Session 真正使用的版本。Worker 只能读取自己当前或曾使用的版本。
- 提示词、模型返回值和外部消息都不能更改 Engine 的授权边界。

## 浏览器与 HTTP 验证

`ManagementServerTest` 使用临时 SQLite、真实 Engine、真实 JDK HTTP 服务和 Java HttpClient，覆盖读页、错误、版本冲突、模板校验、暂停、停止、答复、人工重评、Source 同步和跨 Ticket 资源隔离。

`PanelFixture` 仅放在 `src/test`，创建真实领域记录但不调用外部服务，用于浏览器点击验证。它不是生产入口，也不会在生产空数据库中注入示例 Ticket。

```sh
mvn test
# 编译完成后，以 test classpath 启动：
java -cp '<test-classes>:<classes>:<dependencies>' \
  io.github.dsh.management.PanelFixture /tmp/dsh-panel-qa 3083
```

浏览器需覆盖：中文/英文导航、人工注意项跳转、打开 Thread 原文（包含 HTML 的文本不得执行）、暂停/恢复、设置变更/失败/冲突、提示词编辑/预览/历史、Back/Forward、取消破坏性确认、重复点击和移动端布局。
