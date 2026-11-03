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
