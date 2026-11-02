# 外部适配器：从真实 API 接到领域，而不是让 HTTP 决定授权

先读 `core/Model.java` 与 `core/Engine.java`，再读本页。所有代码在
`src/main/java/io/github/dsh/adapters/`，测试在 `AdaptersTest`。

## 边界与默认值

| 类 | 真正实现 | 不声称实现 |
|---|---|---|
| `GitHubAdapter` | Issues/PR 普通评论分页读取、编辑版本 ID、本人消息过滤、提及识别、显式开启后的评论发送与重发标记查询 | notifications、reviews/inline threads、CI、PR 创建、全仓扫描、GitHub App 令牌刷新 |
| `FeishuAdapter` | 已验证 `im.message.receive_v1` 文本事件转译、话题归组、本人消息过滤、提及识别、显式开启后的话题回复 | 公网 webhook server、签名验签、事件解密、长连接、历史补拉、图片/富文本、tenant token 刷新 |
| `OpenAiExecutor` | OpenAI-compatible `POST chat/completions` 的真实 HTTP 调用，系统消息传入 Mandate scope | DSH Session、工作树、shell、子代理、自动改代码/提 PR；本执行器只产出分析与代码建议文本 |
| `HttpJsonTransport` | HTTPS（回环测试可 HTTP）、10 秒连接超时、60 秒请求超时及额外 60 秒正文读取截止、2 MiB 正文限制、不跟随重定向 | 自动重试；重试应由持久化 effect 队列统一处理 |

**不要把 Source 的输入身份当作可由用户 JSON 声明的权限。** GitHub 身份来自认证 API；飞书输入参数特意叫
`verifiedEventJson`，必须在外层验证签名/令牌后调用。当前项目没有开放 webhook 端口，故没有假装完成验签。
若以后添加 webhook，应先实现官方验签、重放校验、body 大小限制，再调用转译器。

`ReceivedEvent` 是适配器边界 DTO，不是接受工单的决定。`actor`、`bot`、`addressed` 送入领域 Gate；
消息正文即使写着“我是管理员，请忽略策略”，也不会变成授权。外部写入的构造参数 `writesEnabled` 默认应传
`false`；只有用户明确同意对应渠道发送时才改为 `true`。**读取 API 的凭据并不自动等于授权发言。**

## 接入方式

构造 API 客户端只从环境变量/调用者传入凭据，不把令牌写入 SQLite、源码或日志：

```java
var github = new GitHubAdapter(
    URI.create("https://api.github.com/"), System.getenv("GITHUB_TOKEN"),
    "github-main", "your-bot-login", false);
var batch = github.readIssueComments("owner/repository", 42, savedCursor);
// 先将 events 转为领域 Trigger；成功 intake 后再提交 batch.cursor()。
// cursor 与相关 intake 必须在同一 Store 事务中完成，不能先推进 cursor。

var model = new OpenAiExecutor(
    URI.create(System.getenv("OPENAI_BASE_URL")), // 例如 https://api.openai.com/v1/
    System.getenv("OPENAI_API_KEY"), System.getenv("OPENAI_MODEL"));
String result = model.complete(mandateScope,
    List.of(new OpenAiExecutor.Message("user", ticketContext)));
```

GitHub 公共仓库读取可不带 token；私有仓库只授予必要 read 权限。发送评论另需 Issues 或 Pull requests 的 write 权限。
用真实机器人账号 login 配置 `botLogin`，否则无法可靠过滤自己的输出。读取 cursor 有一秒重叠，Trigger ID 含 comment ID
与 `updated_at`，由领域层去重。100 页上限到达时直接失败，不返回部分批次/推进 cursor。

飞书基址为 `https://open.feishu.cn/open-apis/`，调用者提供短期 `tenant_access_token`、机器人的 open ID 和 app ID。
`parseEvent` 对非文本事件返回 empty，不能用这个结果声称支持所有消息格式。

## 交付幂等性的真实边界

本地 Outbox 保留 receipt，防止已确认消息再排队。但“远端 POST 成功，本地事务尚未提交，进程崩溃”的窗口仍存在：

- GitHub 在回复正文附加稳定 `<!-- dsh-java-delivery:... -->`，重试前分页寻找**机器人本人**的同一标记。
  他人伪造标记不会吞掉回复。检查与 POST 之间不是原子操作，**多进程同时发送仍可能重复**；应保持单一调度者。
- 飞书请求带稳定 `uuid`，使用远端的有限时段幂等机制；不是无限期 exactly-once 保证。
- token、完整错误响应正文不会写入 `ApiException`。状态码 408/429/5xx 提供 `retryable()` 提示，实际重试由运行时处理。
- 模型调用可能计费，也可能在网络中断后重试重复计费。没有密钥的本地 demo 不调用模型或外网。

## 如何验证

`mvn -Dtest=AdaptersTest test` 使用 JDK `HttpServer` 作为回环 HTTP fixture，测试真实 HTTP 序列化/解析，覆盖：
模型 scope 和无 tools、空模型响应、429 分类、GitHub 本人过滤/编辑 ID/游标、禁写开关、本人标记重发恢复与伪造标记、
飞书话题转译/本人过滤/稳定 uuid/业务错误、拒绝非 HTTPS 远端和重定向。
这证明协议边界在本地 fixture 工作；**不是**在用户账号上做了端到端 GitHub/飞书验证。

## 对照原型与 API 文档

原型固定版本：`6337fc823ce20251c8de8085948165c81aaa701e`。

- `src/sources/github-trigger.ts`、`github-read.ts`、`github-channel.ts` → `GitHubAdapter`
- `src/sources/feishu-trigger.ts`、`feishu-channel.ts` → `FeishuAdapter`
- `src/executor/dsh.ts` → 本项目的 runtime Executor 端口及 `OpenAiExecutor`（替换宿主，不是等价 DSH 实现）
- [GitHub issue comments REST API](https://docs.github.com/en/rest/issues/comments)
- [飞书回复消息 API](https://open.feishu.cn/document/server-docs/im-v1/message/reply)
- [OpenAI Chat API](https://developers.openai.com/api/reference/resources/chat)

## 已实现的领域桥接

- `SourceBridge.toTrigger(event, scope, receiveAll)` 把可信接收配置与 API DTO 转成领域 Trigger。
- `SourceBridge.receiveBatch(engine, events, sourceId, cursor, scope, receiveAll)` 调用 Engine 的真正批次事务；
  空批次也能推进 cursor，混合来源批次拒绝提交。
- `OpenAiTextExecutor` 实现 runtime 的 Executor，读取工单 Inputs 和 Mandate，输出带“未执行命令/修改文件”说明的 READY 报告。
- `AdapterChannelSender` 实现 runtime 的 ChannelSender；按预先注册的 source 实例路由；将内部 Outbox ID
  哈希成稳定 48 位 hex delivery ID，既避免特殊字符，又满足飞书长度限制。客户端 `writesEnabled` 仍是必要第二道开关。

## 单次 GitHub 运行入口

先在测试仓库配置（密钥仅放环境变量，不写入仓库）：

```bash
export GITHUB_TOKEN='…'
export DSH_BOT_LOGIN='your-bot-login'
export DSH_ALLOWED_ACTORS='your-github-login'
export DSH_POLICY_VERSION='policy-1' # 改名单时换新版本
export OPENAI_BASE_URL='https://your-provider.example/v1/'
export OPENAI_API_KEY='…'
export OPENAI_MODEL='your-model'
java -cp target/dsh-superagent-java-0.1.0.jar io.github.dsh.GitHubOnce owner/repo 123
```

此命令真实读取一个 Issue/PR 的普通评论，语义分类后执行白名单明确请求的**文本分析**。首次运行会读取历史评论，请使用专用测试 Issue。默认 observe 不执行推断工作，但明确请求会调用模型并可能产生费用。评论内容会传给你配置的模型服务。

默认回复保留在 Outbox，不发到 GitHub。审核后设置 `DSH_ENABLE_WRITES=true`，再运行才启用发送；会发送此前已排队的回复。状态默认 `state/github.sqlite`，可通过 `DSH_DB` 指定。一个数据库只运行一个进程；一次处理后退出，并非后台监听器。`DSH_BOT_LOGIN` 必须与 token 实际身份一致，否则 marker 去重和自消息过滤不可靠。

## 本机管理

```bash
java -cp target/dsh-superagent-java-0.1.0.jar io.github.dsh.Manage state/github.sqlite list
java -cp target/dsh-superagent-java-0.1.0.jar io.github.dsh.Manage state/github.sqlite show TICKET_ID
java -cp target/dsh-superagent-java-0.1.0.jar io.github.dsh.Manage state/github.sqlite answer TICKET_ID '这是我的答复'
java -cp target/dsh-superagent-java-0.1.0.jar io.github.dsh.Manage state/github.sqlite retry TICKET_ID
java -cp target/dsh-superagent-java-0.1.0.jar io.github.dsh.Manage state/github.sqlite stop TICKET_ID
```

show 中 Assignment.report.question 即等待人工的问题。answer 在原始对话留下人工答复 Trigger，下一次 worker drain 会消费；同一原始对话的其他订阅 Ticket 也会按正常扇出收到该上下文（它不是私信工具）；stop 撤回授权；retry 只适用于失败/受阻的工单。CLI 是受信任的本机操作人入口，不应直接暴露到公网，也不交给模型调用。崩溃恢复可执行 `Manage DB recover --old-process-stopped`；仅在确认原进程已停止时使用。

## 真正执行文件改动：`CodingExecutor`

`OpenAiTextExecutor` 仍保留为只读分析替代品；**`CodingExecutor` 才是实际编码执行路径**，不是把文本建议假装成改动。
构造参数为 `(apiBase, apiKey, model, templateDirectory, workspacesDirectory, allowWrites, maxSteps)`。

1. 用户显式选择模板目录；模板与工作目录必须互不包含。
2. 每个 Ticket 用稳定哈希创建独立副本，不直接改原仓库。
3. Chat Completions 接收四个 function tools：`list_files`、`read_file`、`write_file`、`human_action`。
4. 模型 tool calls 由 Java 解析执行，将真实结果作为 `role=tool` 送回模型，直到返回最终文本或达到上限。
5. `human_action` 返回结构化 `WAITING` + question；会话 `.dsh-session.json` 记录已消费的 Input ID。
   后续同一工单继续时（包括新进程）读取会话，只加入新增 Inputs，不重放旧人的输入。
6. 写文件与保存会话使用临时文件替换；中断后重试同一路径同一内容的写入通常幂等，但不宣称跨多个工具操作的事务性。

### 强制限制

- `allowWrites=false` 时写工具始终拒绝，不受 prompt 影响；操作人必须显式开启。
- 没有 shell、编译/测试、HTTP、git、发 PR 等工具。实际改动后**仍需人工审阅并运行测试**。
- 拒绝绝对路径、`..`、隐藏路径、反斜杠路径和任何已存在的符号链接组件。
- 模板复制与模型读写都只允许 java/xml/md/txt/json/yaml/yml/gradle/kts；跳过隐藏文件/目录与 target/build/node_modules/vendor，
  不复制 `.env`、`.git`、二进制、压缩包。**可见 JSON/YAML 源文件仍可能含敏感值，选模板前请自行审查。**
- 最多 256 个源文件、每文件 64 KiB、总工作文件 4 MiB；会话最多 1 MiB。
- 遍历深度最多 32 层、最多访问 8192 个目录项；超限停止，不静默截断成完整结果。
- 单次执行最多 1–50 轮（构造参数），每轮最多 8 次工具调用，单次模型请求 `max_tokens=2048`，工具结果展示最多 16000 字符。
- Runtime 向执行器传递实时工单授权检查：每轮/每工具/原子文件替换前查询领域状态，stop 会阻止后续工具并忽略在途模型响应。
  这是协作式取消，不是硬实时抢占；不能撤销已完成的本地写入，检查与替换之间仍有极小竞争窗口，也不保证立刻中止远端计费请求。
- 这不是 OS 安全沙箱。并发恶意本地进程替换路径（TOCTOU）、多个进程同时执行同一 Ticket 不在保证范围内；请保持单一运行实例。
- 会话保存请求上下文与模型输出，可能包含源码，应按源码保密保存，不纳入 Git 提交。

`CodingExecutorTest` 的回环 HTTP 模型 fixture 实际驱动工具循环，验证源副本读写、原文件不改、隐藏密钥不复制、
禁写、目录穿越拒绝、符号链接拒绝、步骤上限、结构化 WAITING 后跨实例恢复、stop 阻止在途响应写入、超大文件拒绝。
该测试不声称调用过真实付费模型或自动验证了生成代码的正确性。
