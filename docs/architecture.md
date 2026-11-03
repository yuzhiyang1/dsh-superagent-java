# 从 Java 后端视角理解架构

本文对应固定上游的 `docs/001-domain.md`、`002-authorization.md`、`003-execution.md` 和 `007-architecture.md`。完整服务入口是 `ServerMain → OrchestratorService`，不是 `Demo`。

## 1. 分清谁决定什么

- **Source**：从可信 API 元数据识别 actor、人/机器人、提及、线程和频道，把正文作为不可信上下文。GitHub 和飞书有各自 scope、游标、接收规则和发送协议。
- **Engine**：独占领域写入，判断准入、授权、所有权和状态变化。模型提出的决定必须重新经过这里。
- **RouterSession**：持久主会话，阅读候选上下文，通过受限工具提出 accept/ignore/attach 等路由决定，不替代授权层。
- **JavaExecutionHost**：每个 Ticket 的持久执行会话，管理目录、模型调用、文件/shell/jobs/子 agent 和结构化报告。
- **Management**：同一数据库的只读投影和可信本机操作入口，不维护第二套业务状态。

`Mandate` 固化工作依据、策略版本和允许范围。转发一条消息、关联一个 Thread 或打开一个工具开关，都不等于允许工单做任意新工作。

## 2. 三个相互关联的状态机

### Thread 与 Eval

每个 Thread 同时最多一个 Eval。已有 Eval 时，新候选进入下一批；上下文关联不建立第二份决策权。评估超时从送达主会话开始计时，暂停期间补偿时钟。迟到决定、过期策略和已经处理的候选不能创建重复工作。

允许人员明确发起的请求与规则推断请求分别处理。推断模式为 `off / observe / auto`；`observe` 保留 `would_accept` 审计，不启动执行。

### Ticket 与授权

Ticket 保存工作意图、输入、订阅、Mandate、PR follow 状态和 Session。它可以包含多次 Assignment。人工 stop 撤回授权，是终止操作；普通消息不能将它恢复。其他结束的 Ticket 只在合适的人类 follow-up 到来、原授权重新有效时恢复。

订阅消息先扇出。非 context-only 的人类 follow-up 由已有 Ticket 拥有，不直接进入新 Eval；全部 owner 退回后才释放。面板的“重新评估”是明确的人工覆盖操作，与模型普通 requeue 不同。

### Assignment 与报告

Assignment 表示一次派工，具有独立生命周期。报告结构包括 progress、waiting、ready、blocked、failed 等，以及明确的问题、回答和退回信息，不能从摘要文字猜测状态。

FAILED/BLOCKED 或投递失败保留可处理的 Ticket；显式 retry 创建新派工并复用 Session/工作目录。旧 Assignment 的迟到报告被拒绝。READY 不一定结束 Ticket：有绑定 PR 时继续跟踪关闭/合并，并避免在仍有未处理输入或执行工作时提前收尾。问题、review、排队和运行阶段由统一逻辑派生，列表与计数使用同一口径。

## 3. 数据库与外部副作用分开

```text
Engine 命令
  └─ SQLite transaction
      ├─ 保存领域状态、输入和审计
      └─ 保存 Effect / Outbox 意图
commit
  └─ Runtime claim（租约与代际）
      ├─ 事务外调用模型、执行宿主、Source 或频道
      └─ 事务内记录报告、回执、重试与下一步
```

Effect 是待执行动作；Outbox 保存回复内容、目标、状态和回执。两者都持久化。运行时按类别处理并发、退避和顺序，同一工单/频道的回复不能越过前一条。关闭来源是等待，不把消息误报成成功；永久消失的目标明确丢弃并记录原因。

外部 API 不能加入 SQLite 事务，因此不能承诺分布式 exactly-once。GitHub 稳定标记、飞书 UUID 和持久工具回执降低重复风险；工具动作结果不确定时先检查，不盲目重新发布。

数据库目录有单实例锁。只在确认旧实例已退出后回收其租约；服务重启恢复忙碌派工，不无故唤醒已经等待或受阻的会话。SQLite 和配置命名空间有版本检查，未知较新版本拒绝打开，迁移保存旧数据以便审计与备份。

## 4. 调度与执行宿主

`PassCoordinator` 串行合并唤醒请求，`SourceScheduler` 按来源周期接入，`OrchestratorRuntime` 执行持久副作用。慢来源/准备/模型工作不应堵住所有类别。worker 有正常并发上限，人类续接有单独的优先路径。

暂停停止新派工、评估计时、输入投递及回复发送，但仍接收来源并保留在途执行事实；恢复后重新检查状态。

`SessionStore` 先保存输入和模型工具调用，再执行工具并保存回执。shell 可转后台 job，子 agent 和 jobs 有所有权、完成唤醒、截止时间和停止处理。prompt 在每次模型请求读取最新版本；已有 worker 的模型选择保存在 Session，新模型设置影响后续新 Session。

## 5. 配置不是直接覆盖 JSON

`VersionedSettings` 的 Policy、Eval、Executor、每个 Source、Pause、Deployment 分别乐观加锁，另保留统一不可变历史。冲突返回 409，恢复旧值仍产生新版本。账户、执行环境、来源写入与发布权限只能从可信面板改变，模型工具不能自行提升。

`PromptService` 保存三个团队段、完整内置部分的覆盖、技能、历史默认文本与当前版本。预览与实际执行共用组装方法；模板只替换一次，未知占位符和过大配置拒绝保存。上游默认变化可以比较旧默认/新默认/团队覆盖，选择保留修改或使用新默认。

面板保存通知与版本同一事务持久化。主会话在后续 pass 收到静默通知，不制造额外对话回复；纯 prompt 更新不主动唤醒空闲 worker。

## 6. 安全边界与测试

文件路径检查、模型工具角色、来源资源可见范围与 loopback HTTP 检查是不同的边界。worker 只能读取自己 Ticket 及可达上下文，不能通过共享 Thread 获得其他 Ticket 的决定或订阅元数据。图片资源保留完整二进制编码，由模型边界转成真实图片输入，不按文本页截断。

这些检查不把任意 shell 变成沙箱。开启 shell 后仍需专用系统账户/容器；网络和发布开关不能代替操作系统隔离。

建议依次运行与阅读：

1. `EngineTest`、`UpstreamParityTest`：授权、状态、follow-up、暂停和 PR 收尾。
2. `RuntimeTest`、`SchedulerParityTest`：崩溃、并发、顺序、退避与回执。
3. `RouterSessionTest`、`JavaExecutionHostTest`：真实模型协议 fixture、文件/Git/进程、工具与会话恢复。
4. `SourceProtocolsTest`：GitHub/飞书协议、游标、图片、事件消费及发送。
5. `ManagementServerTest`、`ConfigurationParityTest`：HTTP 操作、权限、错误码、历史与升级漂移。
6. `ServiceIntegrationTest`、`ServiceSubagentTest`、`ServiceControlTest`：同一常驻组合根下的执行与重启控制。

fixture 使用本地服务替代外部账号，但 SQLite、文件、进程和服务生命周期是真实运行。真实账号部署检查与浏览器交互验收分别记录，不能用 HTTP 200 或单元测试代替。
