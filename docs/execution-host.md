# Java 执行宿主：对照上游阅读

这部分替代 DeepSeek Harness 提供的 Session、Agent、后台 jobs 服务。领域授权仍由 Engine 决定；宿主不根据模型文本创建授权。

## 从哪开始读

1. `runtime/Executor.java`：同步执行、投递、后台状态、持久事件和领域工具边界。
2. `execution/JavaExecutionHost.java`：一次 turn 的模型循环。先持久化输入历史，再请求模型，再检查角色与当前 Assignment，最后执行工具。
3. `execution/SessionStore.java`：收件箱、模型历史、模型选择、提示版本和工具回执的原子保存。
4. `execution/ShellJobs.java`：真实操作系统进程、输出上限、作业所有权、超时和进程树终止。
5. `execution/WorkerEnvironment.java`：无凭据提示变量、命令包装、git 身份配置。
6. `execution/RouterWorkTools.java`：主会话的小型工具集与“不等待”限制。

`CodingExecutor` 的 ExecutionSettings 构造器使用完整宿主。旧构造器保留早期严格文件模式的兼容性，不能把旧模式当成完整服务。

## 与上游的行为对应

| 上游行为 | Java 实现 | 验证 |
| --- | --- | --- |
| 每个 Ticket 一个持久 worker Session，模型创建时固定 | SessionStore，worker-<ticketId>，后续模型设置只影响新 Session | 重建宿主后模型和输入去重测试 |
| 每轮新消息在下一步到达 | deliver 持久收件箱，run 每步 take；报告/正常结束后确认已读 | 模型请求中途注入输入测试 |
| worker shell 5 秒后转后台，子 agent 60 秒 | ShellJobs + 不同 foregroundWait | 真正的 sleep/printf 进程测试 |
| 后台工作保留 worker slot，完成后继续原会话 | backgroundState + 持久 WAKE 事件 | 完成唤醒测试 |
| turn 后 30 分钟限制，杀 jobs，不杀仍在运行的子 agent | backgroundLimit + REPORT/WAITING | 缩短时间的截止测试 |
| 人工交接释放 slot，停止子 agent，保留 shell，不由其完成唤醒 | handedOff 标记、子进程终止和收件箱注入 | 交接后服务器完成、无唤醒测试 |
| Assignment 结束停止整棵子 agent 树和 jobs | stop、取消检查、最长 shutdownTimeout 等待 | 子 agent 迟到写入被拒绝测试 |
| 首次目录准备 10 分钟；支持 git worktree，重试不丢工作区 | prepareCommand / gitRepository；稳定 shortName、branch、directory | 真实临时 Git 仓库和 worktree 测试 |
| 临时模型错误最多重试三次 | 408、429、5xx、连接/超时类错误；指数退避 | HTTP 503 fixture 三次后恢复 |
| 准备失败不自动重试 | PrepareFailed 独立异常；空目录清理 | 失败次数与显式重试测试 |
| 六个 worker 领域工具 | Context 回调到 WorkerDomainTools/Engine | ack/follow/handback/progress 与元数据测试 |
| 文件、搜索、图片、shell、jobs、skills、todos、子 agent | 内建实际工具；图片作为模型多模态 image_url 发送 | 文件、图片、进程和子 agent fixture |
| PR 实际创建及认领 | 显式 allowPublish + PullRequests 适配器；默认 draft | 适配器协议 fixture 与调用测试 |
| 主会话不 sleep，不长等 | RouterWorkTools 的命令和 job wait 守卫 | 拒绝用例 |
| 重启恢复未完成动作 | 保留历史；不确定工具回执明确要求检查，不盲目重放 | 中断写工具回执测试 |
| 配置热更新不重启活跃会话 | reconfigure 原地替换设置；不创建第二份 session/job 管理器 | 活跃 job 与模型设置测试 |

## 能力开关和操作系统边界

默认文件只读，shell、网络工具、子 agent、发布分别需要显式开启。开启 shell 后，命令按运行服务的操作系统账户执行，与上游一样不是容器或安全沙箱。文件工具的路径检查、工具角色限制和 CLI 包装不能阻止一个被允许的任意 shell 程序调用其他网络或绝对路径程序；需要操作系统级隔离时，应把整个服务放在专用账户或容器中。

ExecutionSettings 的环境变量禁止凭据类名称。模型看到 Ticket 目录、短名、来源与账户等普通值，不会收到 API key。命令进程移除继承环境中的 KEY、PASSWORD、SECRET、TOKEN、CREDENTIAL 变量以及 SSH agent 和 git 配置覆盖变量。

可配置的账户 CLI 包装：

- DSH_SUPERAGENT_GITHUB_CLI → gh：代理账户。
- DSH_SUPERAGENT_FEISHU_CLI → lark-cli：机器人账户。
- DSH_SUPERAGENT_MERGE_CLI → gh-merge：明确配置的另一合并账户。

合并资格仍由 Delivery requirements 提示规定，宿主不添加自动合并判断。没有配置另一合并账户，就让人合并。请在部署的 GitHub 分支保护中真正要求审批。

## 崩溃与外部副作用

宿主先保存模型的工具调用，再执行工具，再保存回执。进程可能在外部副作用成功之后、回执写入之前崩溃。这种情况下，恢复会话收到“结果不确定，先检查”的工具结果，不自动重做发布动作。PR 适配器也会先查匹配的已存在 PR，降低重复创建风险。这不是声称所有外部系统都支持严格 exactly-once。

## 运行测试

执行 `mvn test`。`JavaExecutionHostTest` 使用 loopback HTTP 模型 fixture，但文件、Git worktree、shell 输出、进程超时和取消都是真实实现，不需要模型密钥，也不会向外部账户写入。

## 模型供应商与上下文

`DSH_MODEL_PROVIDERS` 是 JSON 数组。例如配置两个端点时，只写凭据的环境变量名：

```json
[{"id":"deepseek","name":"DeepSeek","baseUrl":"https://api.deepseek.com/","apiKeyEnv":"DEEPSEEK_API_KEY"},{"id":"local","name":"Local server","baseUrl":"http://127.0.0.1:8000/v1/","apiKeyEnv":"LOCAL_MODEL_KEY","authRequired":false}]
```

启动默认端点仍由 OPENAI_BASE_URL / OPENAI_API_KEY / OPENAI_MODEL 设置，供应商 id 是 default。面板模型列表实际请求每个端点的 GET /models，分别显示成功目录与失败，不把配置值当作模型发现结果。公开远程端点默认要求密钥；回环地址默认不要求，authRequired 可明确指定。所选角色使用该供应商自己的凭据环境变量。

Session 保存 provider、model、reasoningEffort，后续请求发送到同一选择，并把 reasoningEffort 放入 reasoning_effort。修改设置只影响之后新建的会话；已有会话和它的子 agent 保留创建时的选择。子 agent 可通过 subagent 的 provider/model/reasoning_effort 参数选择已配置模型，list_subagent_models 查询真实供应商目录。

完整原始会话留在状态目录。模型请求中的长工具结果按上游 preset 的 8192 字符门槛、4096 字符头部和 1024 字符尾部剪裁。上下文接近配置窗口的 80% 时，使用原版 compaction 指令生成持久 checkpoint，并保留约 16% 的近期上下文。Java 宿主使用保守的 UTF-8 字节估算，而不是声称与各供应商的私有 tokenizer 完全相同；ExecutionSettings.contextWindowTokens 用于匹配部署模型窗口，默认 128000（常驻服务可用 `DSH_CONTEXT_WINDOW_TOKENS` 配置）。requestCompaction 请求在下一次模型步骤进行同一压缩流程。

编码 persona 原文来自固定上游提交的 cordis.patch.yml，原版 compaction 文本来自 @deepseek-ai/dsh-compaction-basic@0.2.1-alpha.2。公开 npm 包下载后验证了发布的 SHA-512；没有执行包里的 JavaScript。确切来源、版本和完整性值在 [prompt-provenance.json](../third-party/deepseek-harness/prompt-provenance.json)，对应 [MIT 许可证](../third-party/deepseek-harness/LICENSE) 随项目保留。dsh-system-prompt 本身是组装注册表，并不包含另一个隐藏的长编码 prompt。

工作区说明读取 AGENTS.md、CLAUDE.md 及对应 .local.md，按根目录到更具体目录的顺序组合。模型触及子目录后，下一步读取该目录的指令；新发现目录指令时，写操作会要求模型先看新上下文再重试。主会话不读取 worker 仓库说明。

## 服务控制脚本

完成 mvn package 后运行：

```sh
scripts/dsh-superagent start --state-dir "$PWD/state" --port 8080
scripts/dsh-superagent status --state-dir "$PWD/state" --port 8080
scripts/dsh-superagent stop --state-dir "$PWD/state" --port 8080
scripts/dsh-superagent restart --state-dir "$PWD/state" --port 8080
```

脚本只定位 JAR，真正控制逻辑是 Java 的 ServiceControl。可用 DSH_SUPERAGENT_JAR 指定产物，--profile 区分默认状态目录。退出码沿用上游约定：0 正常，1 停止/重复/不可达/运行时错误，2 某个组件错误，3 等待设置。

stop 写入 manually-stopped 标记。监控使用 start/restart --respect-stop 时不会违反手动停止；显式 start/restart 清除标记。停止前核对实际进程主类、完整状态目录和启动时间，不信任单独的 PID 文件，也不会强杀超时进程。

独立 Java 日志 relay 将服务输出写到 state/logs，每个文件最多 5 MiB，只保留最近 20 个。ServiceControlTest 实际启动一个独立 JVM 服务、访问其管理 API、检查退出码并停止，不是仅 mock 进程列表。
