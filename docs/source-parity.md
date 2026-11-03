# Source 验收矩阵（固定上游 6337fc823ce20251c8de8085948165c81aaa701e）

本矩阵将协议能力与测试逐项对应，不能用「HTTP 可以调用」代替业务行为复刻。

| 上游模块 | Java 接口/实现目标 | 验收 |
|---|---|---|
| registry/kind/host/poll | Source、SourceRegistry、SourceContext；动态启用、健康、隔离失败、持久游标、14 天订阅窗口 | 重启/禁用/注册冲突/某源失败不阻塞其它源 |
| github notifications | 通知分页、仓库隔离、X-Poll-Interval、重试记录及 24 小时放弃 | 分页失败不漏读；404重试、410终止；通知游标与重试原子保存 |
| github repository streams | Issues/PR/普通与 inline 评论，按 scope 独立启停，启用时间基线、重叠游标与 ETag | 无历史误接单、编辑 ID、条件 GET、单流失败隔离 |
| github subscribed threads | 人类/机器人/自身过滤、review decision、保留线程轮询与上下文读取 | 普通评论、inline、空正文 approval、父评论排序 |
| github CI | checks+statuses、去重 status、PR 全快照变化、默认分支失败集合/恢复、首轮基线 | 正在运行不提前报恢复；修订 ID 不重用 |
| github pull lifecycle | 验证本人同仓分支、唯一绑定、重开、关闭/合并调用领域、IM结束通知 | 外仓/冒名/多工单拒绝；保留订阅 |
| github channel/read | 去重 marker、review 根回复、已删除 inline 降级、gone语义；issue/PR/review/check/file/dir资源 | 本人标记、重复发送、路径限制、分页 |
| feishu ingress | 实际接收器、安全验证、事件去重、文字/富文本/图片、direct/mention/chat scope | 验签失败不进入领域；自身消息过滤 |
| feishu history/thread | 已知私聊与配置群补拉、话题跟踪/清理、订阅话题、游标回退5秒 | 分页不前进失败、chat/thread隔离、重启恢复 |
| feishu context/image | trigger锚定message/root/parent/thread/around，真实图片资源下载 | 不允许任意消息/图片ID越权；类型与大小限制 |
| feishu channel/policy | 当前来源仍允许目的地、稳定UUID、群名刷新、完整成员名单同步 | 禁用群不发送；部分成员页失败不改名单 |

Java 使用官方 HTTPS API；原型中的 gh/lark CLI 进程属于部署传输实现，不要求 Node/TypeScript。
测试以真实本地 HTTP server 驱动 Java 协议客户端，运行真实 SQLite 领域；不使用真实账号写入。

## 实现与证据

- 来源 SPI 与 registry：`SourceInstance`、`SourceRegistry`（register/upsert/remove/read/readContext/send/find）、`SourceContext.polled`；`sourceHorizonIncludesRecentTicketEndAndExcludesOldIdleThread`。
- GitHub 通知：`GitHubSource.notifications/readThread`；`notificationsHonorPollIntervalAndRepositoryBoundary`、`failedNotificationPersistsRetryAndLaterRecovers`、`deletedNotificationDropsBut404DoesNot`、`staleNotificationRetryExpiresAfterOneDay`。
- 仓库观察/条件请求：`GitHubSource.stream`；`repositoryStreamDoesNotIntakeBeforeEnableAndRetainsEtag`。不同 comment 命名空间：`githubCommentNamespacesAreDistinctAndBodylessApprovalIsEvent`。
- CI：`GitHubProtocol.Ci` 与 `GitHubSource.prCi/branchCi`；`ciBaselineFailureAndPendingRecoveryAreDifferent`、`latestCommitStatusWinsAndChecksAreSorted`。
- PR 绑定和发送：`GitHubSource.claimPull/observePull`、`GitHubPullRequests`；`pullBindingRejectsForeignOwnerAndClosesBoundTicket`、`createDraftPrIsRealPostAndExistingHeadIsReused`、`inlineReplyUsesRootAndDeletedTargetFallsBack`。
- 官方 API 和可执行包装器：`GitHubProtocol`、`FeishuProtocol`、`SourceCommand`；`ghWrapperOwnsAuthenticationAndPreservesStatusHeaders`、`larkWrapperRenderedMessagesThreadsAndBotIdentityNeedNoToken`。
- 飞书本地接收器：`FeishuEventConsumer`；`cliConsumerReallyStartsReconnectsDeduplicatesAndStops` 验证至少三次真实进程重连，防止 guard 的清理中断误关整个 supervisor。
- HTTP 接收器：`FeishuWebhookReceiver`；签名、重复事件、时间窗、令牌、应用身份、AES challenge 和回环绑定 fixture。
- 上下文：`FeishuSource.read/readContext` 与 `GitHubSource.readContext`；锚定范围、thread/chat 隔离、坏条目跳过、分页不前进、merged_forward、无 topic 邻居、图片类型与归属、原始 inline 资源 fixture。
- 策略同步：`FeishuSource.readMembers` 只读完整名单，管理/服务通过版本化设置提交；成员分页异常不产生部分更新。
- IM 文本限制：`SourceText` 固定 ICU4J77.1 中文分词；`unicodeWordCountMatchesMixedLanguageAndRejectsLongImBeforeWrite`。

正式来源支持官方 HTTPS REST 和原型 `gh`/`lark` 包装器两种传输。后端全部为 Java21，不运行上游 TypeScript。测试不需要真实凭据、不向外部账号写入；真实服务权限/限流验证仍须部署账号。
