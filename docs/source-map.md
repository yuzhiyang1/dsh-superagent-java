# 上游源码与 Java 对照

基线固定为 [deepseek-ai/dsh-superagent-prototype @ 6337fc823ce20251c8de8085948165c81aaa701e](https://github.com/deepseek-ai/dsh-superagent-prototype/tree/6337fc823ce20251c8de8085948165c81aaa701e)。以该提交发布的文档、代码与测试为对齐对象，不将论文里未发布的能力算入验收，也不自动跟随上游更新。

Java 版目标是完整重写公开原型。它不是逐行翻译，也不是仍须装进 DSH 的插件。公开功能逐项对应及验证状态见 [验收矩阵](parity/acceptance.md)；本表帮助定位实现，不替代最终验收结论。

## 先读文档，再追 Java

| 上游阅读顺序 | 要回答的问题 | Java 入口 |
| --- | --- | --- |
| [001-domain](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/docs/001-domain.md) | 消息、线程、决定、工单分别是什么？ | `core/Model.java`，再看 `Engine.receive` |
| [002-authorization](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/docs/002-authorization.md) | 何时能接受工作，哪些消息只是上下文？ | `Engine` 的准入、Eval、Mandate、follow-up 与 attach |
| [003-execution](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/docs/003-execution.md) | 派工、后台任务、PR、暂停和恢复怎样协作？ | `runtime/OrchestratorRuntime.java`、`execution/JavaExecutionHost.java` |
| [004-prompts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/docs/004-prompts.md) | 主会话与 worker 分别知道什么、能用什么工具？ | `router/MainTools.java`、`runtime/WorkerDomainTools.java`、`config/PromptService.java` |
| [005-panel](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/docs/005-panel.md) | 人怎样看状态、纠正路由、处理问题？ | `management/`、`src/main/resources/web/` |
| [006-deployment](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/docs/006-deployment.md) | 账户、来源、工作目录和部署风险怎么设置？ | `ServerMain.java`、`service/`、`VersionedSettings.java` |
| [007-architecture](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/docs/007-architecture.md) | 哪些必须在事务内，哪些必须在事务外？ | `core/SqliteStore.java`、`runtime/`、`service/OrchestratorService.java` |

需要背景再补 `000-philosophy.md`；扩展来源时读 `008-cookbook.md` 和 `sources/SourceInstance.java`。不必一开始读完所有协议细节。

## 实现对照

Java 路径均相对于 `src/main/java/io/github/dsh/`。

| 上游模块 | Java 对应 | 阅读重点 |
| --- | --- | --- |
| `src/core/model.ts`, `ports.ts` | `core/Model.java`，`runtime/Executor.java`、`ChannelSender.java` | record/enum/端口；`ThreadState` 避免与 JDK Thread 重名 |
| `src/core/engine.ts`, `intake.ts` | `core/Engine.java` 的 receive/receiveBatch | 去重、关联、来源审计、订阅扇出，事件与游标原子提交 |
| `src/core/evals.ts`, `policy.ts` | Engine 的 evaluateDue/decide 与 Policy | 显式/推断、observe 审计、过期决定、送达计时、暂停与策略变化 |
| `src/core/tickets.ts`, `assignments.ts` | Engine 的 report/attach/stop/retry/follow/handback | 不可变 Mandate、Assignment 独立生命周期、PR 收尾和注意项 |
| `src/app/store.ts`, `migration.ts` | `core/SqliteStore.java`、`config/ConfigurationMigrations.java` | JDBC 事务、持久状态、schema 检查、配置历史和一次性迁移 |
| `src/app/effects.ts`, `outbox.ts` | Store + `runtime/OrchestratorRuntime.java` | 租约代际、并发类别、退避、顺序、失败与持久回执 |
| 应用 pass/调度与健康 | `runtime/PassCoordinator.java`、`HealthBoard.java`、`service/SourceScheduler.java` | 合并唤醒、来源周期、运行负载与错误归属 |
| 主 Session、评估及路由工具 | `router/RouterSession.java`、`CandidateContext.java`、`AssessmentValidator.java`、`MainTools.java` | 有界上下文读取、真实 tool calling、决定回验和持久会话 |
| `src/executor/dsh.ts` 与 DSH Session/Agent/jobs | `adapters/CodingExecutor.java`、`execution/JavaExecutionHost.java`、`SessionStore.java`、`ShellJobs.java` | Java 宿主、模型历史、工具回执、后台任务、子 agent、停止与恢复 |
| 工作目录、账户命令和发布环境 | `execution/WorkerEnvironment.java`、`WorkspaceFiles.java`、`RouterWorkTools.java` | prepare、稳定目录/分支、Git worktree、命令包装与角色限制 |
| 模型供应商与模型设置 | `models/ModelProviders.java`、`ExecutionSettings.java` | 实际 `/models`、供应商选择、reasoning effort、已有 worker 的模型绑定 |
| `src/sources/github*.ts` | `sources/GitHubSource.java`、`GitHubProtocol.java`、`GitHubPullRequests.java` | notifications、观察流、评论/评审、CI、PR 生命周期、资源和回复 |
| `src/sources/feishu*.ts` | `sources/FeishuSource.java`、`FeishuProtocol.java`、`FeishuEventConsumer.java` | 事件消费、历史补拉、消息归一化、上下文/图片、群成员与回复 |
| Source 注册/接口/上下文 | `sources/SourceKind.java`、`SourceKinds.java`、`SourceInstance.java`、`SourceRegistry.java`、`SourceContext.java` | 来源读写隔离、scope、配置、查找、健康与生命周期 |
| `src/app/resources.ts` | `management/ResourceService.java` | 分页上限、worker 的自己工单/可达消息权限、图片与提示版本读取 |
| `src/app/settings.ts`, `versions.ts` | `config/VersionedSettings.java` | 每块 revision、不可变历史、乐观冲突、工具权限与热加载 |
| `src/app/prompts.ts`、内置 prompts/skills | `config/PromptService.java`、`src/main/resources/prompts/`、`skills/` | 三个团队段、15 部分、技能、单次模板填充、升级默认比较 |
| `src/app/api.ts`, `http.ts`, `views/` | `management/ManagementServer.java`、`ManagementViews.java`、`sources/SourceKind.java` | HTTP 契约、实际领域投影、操作、错误码、同源与 body 限制 |
| `src/client/` | `src/main/resources/web/index.html`、`app.js`、`app.css` | 六页导航、深链接、搜索、时间线、草稿、冲突与提示预览 |
| Plugin Manager / SETUP / daemon 生命周期 | `ServerMain.java`、`service/OrchestratorService.java`、`ServiceControl.java`、`StateDirectoryLock.java` | 独立 Java 服务、部署检查、状态目录锁、关闭和重启控制 |

更细的协议对照见 [source-parity](source-parity.md)；工具与宿主对照见 [execution-host](execution-host.md)；HTTP 与配置见 [management-console](management-console.md)。

## 技术替换，不是功能删减

- **运行时**：DSH/Node 宿主改为 Java 21 持久会话、虚拟线程、进程与工具管理。默认 JAR 启动真实常驻服务；旧 `Demo` 和一次性入口仅是额外学习例子。
- **前端**：React/TypeScript 改为原生浏览器 JavaScript；后端无 Node 进程。页面仍使用真实 API，不能以静态页面或 JSON dump 代替管理功能。
- **存储**：JDBC/SQLite 的聚合记录和持久队列替代原型的具体表布局，保留事务与恢复语义。不是直接打开上游数据库文件的二进制兼容迁移器；请勿把原部署数据库直接覆盖到 Java 状态目录。
- **模型与账户**：通过 OpenAI-compatible 协议和可注册供应商连接模型；来源支持专用账户 CLI 和 HTTPS REST。密钥来自部署环境，面板只保存变量名。
- **安全默认值**：来源写入、执行器文件写入、shell、网络工具、子 agent、发布默认关闭，可在可信面板分别开启。启用后的能力真实执行；开关不能替代工单授权，更不能把 shell 变成容器沙箱。
- **部署**：独立进程和本机 Deployment 面板替代 DSH Plugin Manager。单状态目录单实例，不宣称集群部署或分布式 exactly-once。

## 如何判断一项真的完成

以实现、错误路径测试、集成测试及独立验收一起判断，而不是看方法名或 HTTP 200。服务测试必须走同一个组合根，UI 测试必须操作真实记录；本地协议 fixture 与真实账号验证分开记录。当前最终结论以 [验收矩阵](parity/acceptance.md) 为准。

上游 MIT 版权与许可证原文保存在仓库中，提示词与技能保留原文及出处。Java 实现按同一 MIT 许可证发布，不表示 DeepSeek 对此独立项目背书。
