# 原型功能对照与验收证据

基线：`deepseek-ai/dsh-superagent-prototype@6337fc823ce20251c8de8085948165c81aaa701e`。目标是该公开提交的功能和行为重写，不是论文里的未发布系统。后端、工具宿主和服务控制均由 Java 21 实现；浏览器静态 JavaScript 与外部 Git/gh/lark CLI 保留各自职责，不依赖 TypeScript/Python/DSH 运行宿主。

此表把行为落到实现和可运行测试，不把“有一个同名类”当成兼容证据。所有测试都可随 `mvn verify` 重跑。浏览器测试另见 `scripts/ui-e2e` 和 GitHub Actions；真实账号验证单独列出。

| 原版行为 | Java 实现 | 主要行为测试 |
|---|---|---|
| 幂等接入、事务游标、线程关联与订阅扇出 | `core/Engine`, `SqliteStore` | `EngineTest`, `UpstreamParityTest.sourceAndDomainAuditRollbackWithState` |
| 来源身份、明确/推断授权、准入表与 observe/auto/off | `Engine`, `VersionedSettings`, `MainTools` | `senderlessCiUsesAdmissionRowAndDirectUnlistedNeverUsesFollowup`, `inferredObservePreservesWholeAuditWithoutTicket` |
| 串行 Eval 批次、送达才计时、超时重排、策略失效 | `Engine.evaluateDue/candidateDelivered/decide` | `undeliveredCandidateNeverExpires`, `deliveryStartsTimeoutAndThreeExpiriesEndUnavailable`, `policyChangeImmediatelyDiscardsAndRequeuesRunningEval` |
| 不可变 Mandate、结束后重验、stop 撤回 | `Engine`, `Model` | `explicitFollowupMandateRemainsValidWhenSenderRemoved`, `sourceScopeRevocationPreventsResumeAndSelfMessagesAreFiltered` |
| 多 owner、送达/已读/确认/答复/交回、panel 强制评估 | `Engine` | `deliveredButUnreadHumanInputIsReleasedOnEnd`, `readBeforeFinalReportIsNotReleasedButLaterArrivalIs`, `panelCanReleaseOwnedFollowupButModelCannotStealIt` |
| attach 明确指向、上下文订阅不授予权限 | `Engine`, `MainTools` | `contextSubscriptionCannotAcknowledgeAndProtectedThreadsCannotBeDropped`, `offThreadExplicitStopRecordsDecisionAndAttachRequiresReference` |
| Attention、报告、等待人工与阶段优先级 | `Engine.report/ticketStage` | `questionKeepClearAndReplacementWithdrawUnsentAttentionReplies`, `questionAndAnswerReferencesMustBeHumanAndReached` |
| blocked/failed 保留自动输入，人工跟进解除 hold | `Engine`, `OrchestratorRuntime` | `failedWorkerHoldsAutomatedInputsButHumanFollowupReturnsToWork`, `retryRestoresDeliveredUnreadInputAndDeliveryFailureIsRetryable` |
| PR 绑定、ready 等审查、owned 优先、迟到关闭不结束新工作 | `Engine`, `GitHubSource` | `readyWaitsForOwnedPullRequestAndResultCanBePublishedBeforeMerge`, `ownedThreadsTakePriorityOverContextualBoundThreads`, `delayedOldCloseDoesNotEndResumedNewWork` |
| 最终聊天结果与主会话自身操作不重复通知 | `DomainNoticeRelay` | `DomainNoticeRelayTest` |
| 稳定路径/分支、真实 Git worktree、prepare 一次执行 | `JavaExecutionHost`, `WorkerEnvironment` | `localGitWorktreePrepareRunsOnceAndKeepsBranch`, `prepareFailureIsNotAutomaticallyRetriedAndEmptyDirectoryCanRetry` |
| 并发上限、一个人工/status 优先额外槽、清理优先 | `OrchestratorRuntime` | `SchedulerParityTest` |
| shell jobs、后台超时、handoff、嵌套子代理及撤权 | `ShellJobs`, `JavaExecutionHost` | `realShellBackgroundKeepsSlotAndCompletionWakes`, `handoffLeavesServerRunningWithoutSlotOrCompletionWake`, `nestedChildrenShareWorkPermissionsAndReturnToParent` |
| 忙碌恢复新 Assignment、稳定会话、未知副作用不盲重放 | `Engine`, `SessionStore`, `RouterSession` | `interruptionRestartsBusyAssignmentWithRecoveryNote`, `crashRecoveryRepairsPendingToolWithoutBlindReplay`, `RouterSessionTest` |
| 暂停仍接入，冻结评估/派工/回信/超时 | `Engine`, `OrchestratorRuntime`, `SourceScheduler` | `pauseShiftsOnlyDeliveredDeadlineAndQueuesIncomingWork`, `pauseHoldsLaunchAndReplyButStillAllowsStopping` |
| effect 租约隔离、合并、重试、会话内顺序发件箱 | `SqliteStore`, `OrchestratorRuntime` | `staleEffectLeaseCannotCompleteOrFailNewGeneration`, `coalescedEffectPreservesLatestPayloadAgainstStaleCompletion`, `failedReplyBlocksLaterSameConversationUntilThirdFailureButNotOtherTicket` |
| 14 个主会话工具、6 个 worker 工具、身份绑定 | `MainTools`, `WorkerDomainTools` | `RouterToolsTest`, `ExecutionHostTest` |
| 完整提示词/skills、逐次请求更新、版本/漂移/恢复 | `PromptService`, `WorkspaceInstructions` | `ConfigurationParityTest`, `ServiceSubagentTest` |
| 接受规则全文与沟通优先级，后续规则变更上下文 | `Ticket.matchedRuleInstructions`, `Engine`, `JavaExecutionHost` | `UpstreamParityTest`, `ServiceSubagentTest` |
| 真实模型目录、供应商选择、reasoning、既有会话保留模型 | `ModelProviders`, `RouterSession`, `JavaExecutionHost` | `ModelProvidersTest`, `RouterSessionTest` |
| GitHub 通知/仓库流/编辑/审查/CI/PR 生命周期 | `GitHubSource`, `GitHubProtocol` | `SourceProtocolsTest` |
| GitHub 游标、失效通知、inline 回复回退、真实 PR API | `GitHubSource`, `GitHubProtocol`, `GitHubPullRequests` | `failedNotificationPersistsRetryAndLaterRecovers`, `inlineReplyUsesRootAndDeletedTargetFallsBack`, `createDraftPrIsRealPostAndExistingHeadIsReused` |
| 飞书实时 CLI 接收、重连/回补、范围过滤、原始上下文 | `FeishuEventConsumer`, `FeishuSource`, `FeishuProtocol` | `cliConsumerReallyStartsReconnectsDeduplicatesAndStops`, `feishuBackfillUsesAtomicCursorAndRejectsStuckPages` |
| 飞书图片来源验证、实际已读取邻居授权、视觉模型输入 | `FeishuProtocol`, `ResourceService`, `OrchestratorService` | `ServiceSourcesTest`, `RouterSessionTest`, `ExecutionHostTest` |
| 同步成员名单必须持久化策略、分页错误不误清空 | `MainTools`, `VersionedSettings`, `FeishuSource` | `policySyncActuallySavesAndReportsMembershipDifference`, `feishuMemberReadDoesNotSilentlyMutatePolicyAndChecksPagination` |
| 六页控制台、版本冲突、源字段/策略生成、搜索及深链 | `ManagementServer`, `ManagementViews`, `static/` | `ManagementServerTest`, `ConfigurationParityTest`, `scripts/ui-e2e` |
| CSRF/Origin/Host 限制、资源隔离、防越权配置 | `ManagementServer`, `ResourceService`, `OrchestratorService` | `enforcesOriginCsrfHostAndBoundedBodies`, `workerResourcesRejectOtherTicketAndSharedThreadMetadataLeaks`, `ServiceSourcesTest` |
| 常驻服务、控制命令、人工停止标记、限额日志 | `ServerMain`, `ServiceControl`, `StateDirectoryLock` | `ServiceControlTest`, `ServiceIntegrationTest` |
| schema 迁移、版本拒绝、手工备份回滚 | `SqliteStore`, `ConfigurationMigrations` | `schemaOneMigrationReconstructsEndAndPendingInputFacts`, `settingsMigrationIsAtomicIdempotentAndRejectsNewerSchemas`；操作见 `docs/deployment.md` |
| SourceKind 扩展、生成设置/策略字段、作用域/健康/环境 | `SourceKind`, `SourceKinds`, `VersionedSettings`, `OrchestratorService` | 自定义第三来源的配置、接入、上下文、发送、热更新及卸载 fixture |

## 验证层次

- **领域和真实本地运行**：真实 SQLite 事务、实际 HTTP 服务、Git worktree、shell 进程、子代理模型工具循环、重启/恢复与暂停均由测试驱动，不是整个系统的假实现。
- **外部协议**：GitHub、飞书及模型供应商使用本地 HTTP/CLI fixture 验证请求、响应、分页、失败与重试。测试不使用线上密钥，不向真实测试外账号发消息或建 PR。
- **浏览器**：使用真实 Java 服务与 SQLite seeded 数据；Playwright 操作页面而非 mock DOM。需要 CI 的浏览器运行结果和报告，不以 HTTP 200 或 JS 语法正确替代实际交互验收。
- **线上账号**：没有用户的模型、GitHub bot、飞书应用密钥，故未替用户验证真实账号、组织策略、模型工具能力与部署网络。这是账户验证边界，不是缺少对应实现。

任意 shell 以服务 OS 用户权限执行，**不是安全沙箱**。文件写入、shell、联网、子代理、发布和来源回信使用显式开关，功能路径齐全；默认不开启危险能力。生产运行应使用专用低权限账号和隔离工作目录。
