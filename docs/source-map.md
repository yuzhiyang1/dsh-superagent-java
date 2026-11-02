# 源码对照与移植范围

上游固定版本：[`6337fc823ce20251c8de8085948165c81aaa701e`](https://github.com/deepseek-ai/dsh-superagent-prototype/tree/6337fc823ce20251c8de8085948165c81aaa701e)。这份 Java 重写以该提交的文档与实现为基线，不跟随上游自动变更。

| 原版模块 | Java 对照 | 保留 / 简化 |
|---|---|---|
| `src/core/model.ts`, `ports.ts` | `core/Model.java`, `runtime/Executor.java`, `ChannelSender.java` | 领域词汇与端口；Java `ThreadState` 避免与 JDK Thread 混淆 |
| `src/core/engine.ts`, `intake.ts` | `core/Engine.java` receive / receiveBatch | 原子命令、去重、关联、订阅扇出、cursor 原子提交 |
| `src/core/evals.ts`, `policy.ts` | Engine evaluateDue / decide / gate | 显式/推断、OFF/OBSERVE/AUTO、规则匹配、策略版本、超时；集中到一个类以便跟读 |
| `src/core/tickets.ts` | Engine followThread / attach / stopTicket / retryTicket | 工单与派工分离、人工 stop、resume、handback、context-only |
| `src/core/assignments.ts` | Engine report / acknowledge | 结构化报告、拒绝旧 Assignment、未处理 follow-up 释放 |
| `src/app/store.ts` | `core/SqliteStore.java` | 真实 SQLite；聚合 JSON 表代替上游多表和 generated-column 索引，适合小规模学习 |
| `src/app/effects.ts` | SqliteStore effect + `OrchestratorRuntime` | 事务入队、持久化退避、租约/重启恢复；单进程串行，而非上游每种副作用独立并发队列 |
| `src/app/outbox.ts` | Engine queueReply / markReplySent + Runtime | 持久化回执、防重复入队；没有完整渠道永久失效分类/撤回 UI |
| `src/executor/dsh.ts` | `WorkspaceAnalysisExecutor`, `OpenAiTextExecutor`, `CodingExecutor` | 替换 DSH 专用 Session：本地只读分析 / Chat Completions 文本生成 / 受限文件工具编码循环 |
| `src/sources/github*.ts` | `adapters/GitHubAdapter.java` | Issue/PR 普通评论分页读取、自己的消息过滤、显式开启回复；未移植 notifications、review threads、CI、PR 生命周期跟踪 |
| `src/sources/feishu*.ts` | `adapters/FeishuAdapter.java` | 已验证文本事件转换、回复；不是公网 webhook 服务，无签名验证/长连接/图片/历史补偿 |
| `src/client`, HTTP 管理接口 | 无 | 没有 Web 面板；通过 Manage CLI / Java API 和测试观察状态 |
| prompts、DSH 工具/技能、部署安装器 | `ModelAssessor` 的窄文本分类提示与 docs | 不是原版完整 prompt/config/技能系统；不运行原版 SETUP |

## 重要语义差异

- 原版多个独立服务类被合并到 Engine，降低跨文件跳转成本；并非逐行翻译。
- `WOULD_ACCEPT` 是 Java 版用于展示 observe 的明确记录，不代表原版存在同名 Decision。
- 超时从 Eval 创建计时；原版从 assessor delivery 开始，并支持 pause 补偿。此版没有 processing pause 与动态 budget。
- `READY` 直接结束 Ticket；没有上游“自己绑定 PR 尚未 merged/closed 则继续等”的收尾逻辑。
- 单进程、单实例数据库：没有集群选主、多个 worker 服务或分布式 exactly-once。
- 编码执行器可在独立源码副本内读写文件，并支持人工问题/恢复，但不会启动 shell、提交 commit 或创建 PR。它没有原版 DSH 完整工具集；不能只给模型 unrestricted shell。
- GitHub 轮询入口每次运行一次；不是常驻服务。模型评估只支持受限文本工作的 accept/ignore，人工 stop 与其他管理动作由 Engine API 提供。

这些差异不是隐藏的 TODO。它们定义此仓库的学习边界；核心链路是真实实现，平台外围不是声称已完成的全量产品移植。
