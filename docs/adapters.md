# 来源协议与外部边界

正式服务使用 `sources/SourceRegistry`、`GitHubSource`、`FeishuSource`，不使用早期单次运行示例作为后台来源。先读 `core/Model.java`、`core/Engine.java`，再读 `sources/SourceInstance.java`。

## GitHub

`GitHubSource` 管理通知、仓库观察流、订阅线程、PR 生命周期与 CI；`GitHubProtocol` 负责真实协议和上下文资源。

- notifications 按仓库过滤，遵守 `X-Poll-Interval`，保存每个失败项的重试记录。404 保留重试，410 删除，24 小时过期。
- Issues/PR/普通评论/代码评论独立开关；启用时间基线避免历史洪水。分页完整成功后才事务提交事件与游标。条件 GET 保留 ETag，游标有5秒重叠，由稳定 Trigger ID 去重。
- 订阅 PR 读取 reviews（含空正文审批）、普通及 inline 评论、checks/statuses。PR CI 只作为上下文扇出，不成为主动工作的候选；默认分支 CI 单独使用策略行。
- 本人同仓分支或工单短名的 PR 可以绑定；支持重开、关闭、合并及非 GitHub 对话中的最终结果通知。领域决定是否结束工单，来源不能跳过仍在执行的工作。
- 普通评论和代码评论根回复均可发送；inline 原评论删除后降级到 PR 普通评论。HTML 标记查询只接受机器人本人的记录。
- `GitHubPullRequests` 将执行宿主的创建/读取 PR 端口连接到同一来源客户端，创建必须同时启用来源和写入权限。

传输可以是官方 HTTPS REST，也可以设置 `config.cli` 为已认证 `gh` 或包装器可执行文件的路径。Java 使用 `ProcessBuilder` 参数数组，不经过 shell，不读取或导出包装器的凭据。包装器返回的 HTTP 状态、ETag、轮询间隔和错误仍进入相同的持久化处理逻辑。

## 飞书

`FeishuSource` 负责接入与补拉；`FeishuProtocol` 负责分页、消息归一化、上下文、图片和回复。

- 配置群的 all/mentions、私聊开关、其他群 mentions/none 均由可信来源配置控制。忽略机器人自身；保留提及名称、root/parent/thread、图片键和富文本。
- `config.cli` 支持原型相同的 `lark ... event consume` 本地长连接部署，不需要公开入站地址。Java 监督进程、等待 ready、断线重启、有限退避、停止清理，并通过持久游标补拉与去重。
- HTTP 部署另有 `FeishuWebhookReceiver`：只绑定回环地址，验签/令牌/时间窗、AES 解密、事件收据与接入同事务。公网 TLS 应由受控反向代理提供。
- 已知私聊、配置群和订阅话题补拉；分页未前进或中途失败不推进游标；坏条目单独计数，跨会话数据拒绝；全批按时间排序。
- 上下文资源锚定已保留 Trigger，不接受任意远程 message ID。可读原文、root、parent、topic、前后消息和链接；没有 topic 时上下文退回此前聊天消息。CLI 保留富文本和合并转发；REST 路径展开返回的合并转发子消息。
- 图片键必须出现在对应消息中。下载限制大小、文件路径和 PNG/JPEG/GIF/WebP 类型；结果包含媒体类型和 data URL，供模型边界作为图片输入，不将“返回图片地址”等同于模型已看图。
- 群成员完整分页读取后由 `VersionedSettings` 提交版本化名单；错误或部分结果不能覆盖策略。`syncChat` 属于 `policy.sources.feishu`。
- 发送使用话题回复与稳定幂等 UUID，撤回/永久删除目标返回 gone；来源关闭返回 waiting。`SourceText` 使用 ICU 中文字典分词，发送前执行与上游相同的100词限制。

## 授权、凭据和恢复

`ReceivedEvent` 只是 DTO。`SourceBridge` 把经过认证 API/接收器获得的身份，以及调用者提供的可信 scope/receiveAll 配置转成 Trigger。消息正文不能声明授权。

HTTP 凭据只从环境变量传入，CLI 凭据由操作者配置的包装器管理；不写入数据库或日志。只读权限不表示允许发言，`writesEnabled` 默认关闭。首次启用写入前检查持久化 Outbox，因为此前排队的消息也会发送。

来源 cursor、事件与领域状态同 SQLite 事务提交；远端副作用无法与本地数据库构成分布式事务。GitHub 稳定标记及飞书有限时段 UUID 用于重试去重，但不宣称无限期 exactly-once。单数据库保持一个服务实例。

`HttpJsonTransport` 限制响应大小、连接/请求/正文读取时间，不跟随重定向，只允许 HTTPS（回环 fixture 除外）。`SourceCommand` 限制 CLI 时间和输出，退出清理子进程；外部错误不记录完整正文/密钥。

## 测试和阅读顺序

1. `SourceProtocolsTest`：真实回环 HTTP、真实本地包装器进程及 SQLite。覆盖通知恢复、流基线、PR/CI、上下文、图片、成员、scope、长连接重启、Webhook 加解密和搜索标识。
2. `AdaptersTest`：低层 HTTP 与早期兼容客户端的协议、安全和幂等测试。
3. 服务、领域与执行宿主测试：验证来源接入后真正经过评估、工单、执行、回复及重启恢复。

`GitHubAdapter`、`FeishuAdapter`、`GitHubOnce`、`OpenAiTextExecutor` 保留为独立小示例/兼容端口，并非正式服务功能范围。执行宿主及模型工具循环请读 `execution/` 与对应文档。

固定上游版本：`6337fc823ce20251c8de8085948165c81aaa701e`。逐项对应见 [source-parity.md](source-parity.md)。本地 fixture 不等于已用真实 GitHub/飞书账号验证；真实部署仍须完成账号、权限、机器人身份和模型配置检查。
