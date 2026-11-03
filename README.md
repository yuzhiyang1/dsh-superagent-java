# dsh-superagent-java · Java 21 重写

用 Java 21 重写 [DeepSeek Superagent 公共原型](https://github.com/deepseek-ai/dsh-superagent-prototype)，方便 Java 开发者读源码、调试状态机和学习 Agent 工程。对齐基线固定为 [`6337fc8`](https://github.com/deepseek-ai/dsh-superagent-prototype/tree/6337fc823ce20251c8de8085948165c81aaa701e)，不是官方 DeepSeek 产品。

默认入口是**常驻服务**：真实来源 → 主会话评估 → 工单与执行会话 → 工具执行 → 持久回复，同时提供本机 Web 管理面板。后端全部使用 Java；前端为浏览器原生 JavaScript，无 TypeScript/Node 后端、Spring 或 Lombok。

本次重写以该提交的全部公开功能为对齐目标。逐项验证记录见 [验收矩阵](docs/parity/acceptance.md)；未验收项不能视为已通过，也不把本地协议测试等同于真实账号部署验证。

## 启动

需要 JDK 21、Maven 3.9+。首次构建需要下载 Maven 依赖。

```bash
mvn verify
java -jar target/dsh-superagent-java-1.0.0-SNAPSHOT.jar --state-dir ./state --port 8080
# 开发时也可：mvn exec:java
```

打开终端打印的地址，默认是 `http://127.0.0.1:8080/orchestrator/`。没有模型或来源账户时也能打开面板；Deployment 会显示缺少的配置，不会假装已连接。数据保存在指定状态目录，停止后重启沿用数据库、配置版本和会话历史。

模型使用支持 tool calling 的 OpenAI-compatible Chat Completions 接口。启动前配置：

```bash
export OPENAI_BASE_URL='https://你的模型服务/v1/'
export OPENAI_MODEL='你的模型名称'
# OPENAI_API_KEY 由你自己的安全环境配置注入，不写进仓库或面板
java -jar target/dsh-superagent-java-1.0.0-SNAPSHOT.jar
```

然后在面板完成以下设置：

1. **Settings → Sources**：填写 GitHub 仓库、代理账号，或飞书应用、机器人和监听会话；选择专用账户 CLI 或 REST 连接。REST 配置保存的是令牌**环境变量名**，例如 `GITHUB_TOKEN`，不是令牌本身。
2. **Settings → Eval**：设置允许发起工作的人员、机器人、准入表、规则和主会话模型。推断接受默认 `observe`，先看决定再切换 `auto`；允许人员的明确请求与推断请求不同。
3. **Settings → Execution**：选择 worker 模型、并发数、工作目录和准备命令，按任务需要开启文件写入、shell、网络、子任务、发布权限。
4. 需要来源发送回复时，显式开启该来源的 **Allow replies and publication**。开启会发送 Outbox 中已等待的消息；创建 PR 还要求执行器发布权限。
5. **Settings → Deployment**：运行检查，确认账户与来源健康，再向机器人发送测试请求。

模型供应商可通过 `DSH_MODEL_PROVIDERS` 注册多个端点，模型目录来自各供应商的真实 `/models`。面板分别选择主会话和新 worker 的模型。详见 [执行宿主](docs/execution-host.md) 与 [管理控制台](docs/management-console.md)。

## 可以直接阅读和运行的部分

- **领域与授权**：Trigger、Thread、Eval、Decision、不可变 Mandate、Ticket、Assignment；去重、订阅、follow-up、暂停、重试、人工问题、停止与恢复。
- **常驻编排**：SQLite 事务、持久 Effect/Outbox、租约、退避、分类并发、人工续接优先、来源轮询、重启恢复与单实例锁。
- **Java 执行宿主**：持久模型会话、文件工具、shell 与后台 jobs、子 agent、技能、工作树、来源资源、PR 端口和工具回执。
- **GitHub / 飞书**：通知与观察流、评审/CI/PR 生命周期、飞书事件消费与历史补拉、上下文/图片、成员同步及回复协议。
- **管理与提示词**：Overview、Sources、Triggers、Eval、Execution、Settings 六页；搜索、时间线和操作；完整内置 prompts/skills、角色预览、版本历史、默认更新比较、冲突保护与热加载。

这些能力连接同一份服务数据，不是单独的假数据看板。原有 `Demo`、`CodeOnce` 等小入口保留作单点学习例子，不是正式服务的实现范围。

## 阅读顺序

每次只读一层，先跑测试再往下追调用：

1. [ServerMain](src/main/java/io/github/dsh/ServerMain.java) → [OrchestratorService](src/main/java/io/github/dsh/service/OrchestratorService.java)：服务怎样组装。
2. [Model](src/main/java/io/github/dsh/core/Model.java) → [Engine](src/main/java/io/github/dsh/core/Engine.java)：哪些状态与授权不能交给模型决定。
3. [SqliteStore](src/main/java/io/github/dsh/core/SqliteStore.java) → [OrchestratorRuntime](src/main/java/io/github/dsh/runtime/OrchestratorRuntime.java)：事务与外部副作用怎样分开。
4. [RouterSession](src/main/java/io/github/dsh/router/RouterSession.java) → [JavaExecutionHost](src/main/java/io/github/dsh/execution/JavaExecutionHost.java)：主会话与执行会话的职责。
5. [sources](src/main/java/io/github/dsh/sources) → [config](src/main/java/io/github/dsh/config) → [management](src/main/java/io/github/dsh/management)：真实集成、版本化配置和面板。

配套文档：[源码与上游对照](docs/source-map.md) · [状态与可靠性](docs/architecture.md) · [来源协议](docs/adapters.md) · [执行宿主](docs/execution-host.md) · [管理控制台](docs/management-console.md)。

## 测试与部署边界

`mvn verify` 运行领域、来源协议、路由、执行宿主、管理 HTTP 和常驻服务集成测试。`ServiceIntegrationTest` 使用本地模型协议 fixture，但真正经过服务循环、SQLite、工具写文件、报告、回复和关闭重启；`JavaExecutionHostTest` 使用真实临时 Git/worktree 和无害本地进程。测试不需要真实模型 key，也不向真实 GitHub/飞书账号发送消息。

管理面板仅监听 loopback，检查 Host、Origin、专用请求头和请求大小，**不要直接暴露公网**。外部来源正文不能扩大权限。默认关闭来源写入，以及执行器写文件、shell、网络工具、子 agent 和发布；这些是可显式启用的能力，不是省略实现。

**shell 不是安全沙箱。** 开启后命令以服务的系统账户运行；请使用专用账户或隔离容器，不要让服务接触个人凭据、敏感目录或未授权仓库。发送给模型的源码和上下文会离开本机。状态目录包含会话与消息，备份时同样需要保护。

保留上游 MIT 许可证及提示词/技能原文版权声明，见 [LICENSE](LICENSE)、[NOTICE](NOTICE) 和 [UPSTREAM-LICENSE](src/main/resources/UPSTREAM-LICENSE)。Java 的独立 Session、HTTP 服务、CLI 与浏览器界面是宿主技术替换，不依赖安装原版 DSH 插件。
