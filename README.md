# dsh-superagent-java · JDK 21 学习版

把 [DeepSeek 的研究原型](https://github.com/deepseek-ai/dsh-superagent-prototype) 的核心编排机制改写成 Java，方便 Java 后端开发者直接读实现，而不只是看架构图。

**这是可运行的核心移植，不是完整产品复刻，也不是官方 DeepSeek 产品。** 无 Spring、无 Lombok、无反射式业务框架：Java 21 + JDBC/SQLite + Jackson。保留原项目 MIT 许可证；基线提交见 [NOTICE](NOTICE)。

## 先运行，再读代码

需要 JDK 21、Maven 3.9+。首次构建会从 Maven Central 下载依赖。

```bash
mvn verify
java -jar target/dsh-superagent-java-0.1.0.jar
# 或 mvn exec:java
```

不需要 API key，不访问 GitHub/飞书，不启动 shell。演示创建临时 Java 文件，真正读取并分析源码，然后通过真实 SQLite、事务 Engine、Assignment、持久化 effect 和本地 Channel 走完：

1. 明确请求 → Eval → Mandate → Ticket → 执行源码分析 → Outbox 回执。
2. 重复 Trigger 去重；人的 follow-up 恢复同一 Ticket，创建新 Assignment。
3. 默认 `OBSERVE`：推断请求只记录 `WOULD_ACCEPT`，不启动执行。
4. 人工 stop 撤回授权，旧 Ticket 不可恢复。
5. 关闭并重开 SQLite 验证状态持久化。演示打印数据库位置，不删除学习证据。

## 建议阅读顺序（每次只读一层）

| 顺序 | 文件 | 这一步看懂什么 |
|---|---|---|
| 1 | [Demo.java](src/main/java/io/github/dsh/Demo.java) | 一条请求如何走完系统 |
| 2 | [Model.java](src/main/java/io/github/dsh/core/Model.java) | Source / Trigger / Thread / Eval / Policy / Mandate / Ticket / Assignment / Subscription |
| 3 | [Engine.java](src/main/java/io/github/dsh/core/Engine.java) | 接入、权限、状态转换；所有写入为什么走同一个入口 |
| 4 | [SqliteStore.java](src/main/java/io/github/dsh/core/SqliteStore.java) | JDBC 事务、持久化队列、租约、重试 |
| 5 | [OrchestratorRuntime.java](src/main/java/io/github/dsh/runtime/OrchestratorRuntime.java) | 为什么外部调用必须在事务外 |
| 6 | [adapters](src/main/java/io/github/dsh/adapters) | 把 GitHub/飞书/模型协议隔离在边界 |
| 7 | [测试目录](src/test/java/io/github/dsh) | 用失败场景理解约束，而不仅是 happy path |

配套：[源码对照与移植范围](docs/source-map.md) · [状态与可靠性](docs/architecture.md) · [外部适配器](docs/adapters.md)。

## 和原版是什么关系？

已经实现核心领域与实际持久化执行链，以及 GitHub 普通 Issue/PR 评论读写、飞书文本事件转换/回复、OpenAI-compatible Chat Completions。网络适配器有本地 HTTP fixture 测试，不需要真实账号。

**刻意不伪装已实现的部分：** DSH Session、任意 shell/完整 DSH 工具集、Git worktree/PR 创建、Web 管理面板、飞书长连接/历史回补、GitHub notifications/review/CI 全覆盖、原版所有管理动作与热配置，都没有等价移植。文本执行器仅生成建议；可选编码执行器能修改独立副本，但不能执行 shell、创建 PR 或声称已运行测试。详细差异见源码对照表。

默认不配置来源授权；推断模式为 `OBSERVE`；外部渠道写入须单独显式开启。来源消息是上下文，不是扩大权限的指令。请只在测试仓库中验证真实集成，不要直接生产部署。

## 真正的有界编码执行器（可选，需要模型 key）

`CodingExecutor` 使用 OpenAI-compatible **tool calling** 循环，提供 `list_files/read_file/write_file/human_action`。每个 Ticket 拥有独立源码副本；默认禁写，显式开启后才能编辑副本。没有 shell、联网工具、自动 commit/push。它不是只输出代码建议：测试会验证工具实际改写了文件。

```bash
# 先设置 OPENAI_BASE_URL / OPENAI_API_KEY / OPENAI_MODEL（模型需支持工具调用）
java -cp target/dsh-superagent-java-0.1.0.jar io.github.dsh.CodeOnce \
  state/code.sqlite examples/java-project state/workspaces \
  --allow-file-writes --request '为 Calculator 增加 subtract 方法，解释边界行为'
```

模型请求人工决定时产生 `WAITING` + `question`，用 `Manage ... answer TICKET_ID '答复'` 留下回复，再不带 `--request` 运行同一条 CodeOnce 命令即可继续同一工单。最多 12 轮模型调用/次执行；超限会结构化受阻，不能无限循环。模板只复制受支持源码类型，限制数量/大小，不复制隐藏文件，拒绝符号链接；不能识别普通源码、application.yml 或 config.json 内嵌的秘密。**不要把含敏感内容的目录用作模板；允许的源码内容会发送到配置的模型服务。**

输出路径中是修改后的副本。请人工审查 diff，并在自己的受信任环境运行测试；执行器不会假称已通过它没运行过的编译或测试。更多管理命令见 [适配器文档](docs/adapters.md)。
