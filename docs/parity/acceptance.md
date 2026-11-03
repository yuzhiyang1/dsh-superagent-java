# 公共原型完整功能复刻验收表

基线：deepseek-ai/dsh-superagent-prototype @ 6337fc823ce20251c8de8085948165c81aaa701e。

本表针对该提交真实实现，不包含论文中的未发布设计。旧版 0.1 的 57 个测试仅证明当时的局部实现，不能作为完整复刻验收。本轮每项以实现路径、测试名称和最终验证结果闭环；实现证据见 [对照矩阵](acceptance-matrix.md)。浏览器运行结果与真实账号验证必须分别说明，不能用本地测试替代。

| 编号 | 原文 / 源码依据 | 必须具备的行为 | 责任模块 | 状态 |
|---|---|---|---|---|
| P00 | docs000 | 只在明确授权或规则允许时工作；默认 observe；有用结果/问题才发消息 | Core + Router/prompts | 已实现；验收证据见对照矩阵 |
| D01 | docs001, core/intake | 来源过滤留审计、幂等 Trigger、主线程 Eval、仅上下文关联、订阅扇出 | Core/Source | 已实现；验收证据见对照矩阵 |
| D02 | docs001/002, policy | 每来源身份/白名单/bot/接收范围/准入表；显式推断和三种接受模式 | Core/Settings | 已实现；验收证据见对照矩阵 |
| D03 | docs002, core/evals | 每线程串行批次、延迟送达计时、超时重排/不可用、策略变更废弃重排 | Core/Router | 已实现；验收证据见对照矩阵 |
| D04 | docs002, core/tickets | 不可变 Mandate、结束后重验、人工 stop 撤回、原授权 basis 保存 | Core | 已实现；验收证据见对照矩阵 |
| D05 | docs002 | follow-up 多 owner；ack/read/answer/handback；全部释放再 Eval；panel 强制重评 | Core/Worker | 已实现；验收证据见对照矩阵 |
| D06 | docs002 | attach 必须明确指向，context-only 不授权不恢复，unrelated/protected subscriptions | Core/Router | 已实现；验收证据见对照矩阵 |
| E01 | docs003 | pending/running/ended 派工独立于 Ticket，结构化所有报告、attention/stage | Core/Worker | 已实现；验收证据见对照矩阵 |
| E02 | docs003 | FAILED/BLOCKED 自动输入 hold，真人消息释放，delivery failure，retry 新派工保留 Session/目录 | Core/Runtime | 已实现；验收证据见对照矩阵 |
| E03 | docs003 | PR own/follow 绑定、重开、最后相关关闭结果、unfinished work 防提前结束、聊天最终通知 | Core/Source | 已实现；验收证据见对照矩阵 |
| E04 | docs003 | 默认空目录/显式 prepare；十分钟限制；工作树；稳定短名/分支/环境变量/失败输出 | Execution | 已实现；验收证据见对照矩阵 |
| E05 | docs003 | 正常并发槽 + 人工唤醒额外槽；优先人类续接；慢 prepare 不阻塞其他派工 | Runtime | 已实现；验收证据见对照矩阵 |
| E06 | docs003 | shell 前台5秒转 job；子代理60秒；后台30分钟；完成唤醒；handoff/stop/job/child 生命周期 | Execution/Runtime | 已实现；验收证据见对照矩阵 |
| E07 | docs003 | 重启忙碌派工重建、恢复说明、未进入history输入重送、已读输入不重复 | Core/Execution | 已实现；验收证据见对照矩阵 |
| E08 | docs003 | pause 停调度/Eval时钟/delivery/reply，仍接收来源与在途运行；恢复重验 | Core/Runtime | 已实现；验收证据见对照矩阵 |
| Q01 | docs003/007 | 所有副作用先持久化；租约/代际防误删；分类并发、重试退避和失败健康 | Store/Runtime | 已实现；验收证据见对照矩阵 |
| Q02 | docs003 | relay/notice 顺序；reply 同工单同会话顺序；回执、幂等、off等待、gone/ignored永久丢弃 | Runtime/Source | 已实现；验收证据见对照矩阵 |
| R01 | docs004/007 | 主 Session 14工具、worker6工具、权限资源隔离、no-wait、主worker模型分离 | Router/Execution | 已实现；验收证据见对照矩阵 |
| R02 | docs004 | 全部内建提示、团队章节/技能/模板占位符校验、每步最新版本、历史/预览/default drift | Config/Execution | 已实现；验收证据见对照矩阵 |
| G01 | sources/github* | 通知、可选全仓issue/PR/comment/默认CI流，订阅线程保留两周 | Source | 已实现；验收证据见对照矩阵 |
| G02 | sources/github* | Issue/PR正文/评论/审查/inline thread/无正文approval/checksuite/CI/closed/merged/reopened | Source | 已实现；验收证据见对照矩阵 |
| G03 | sources/github* | 分页和持久游标，逐对象失败隔离、404重试/一天失败丢弃、malformed计数、read resources | Source | 已实现；验收证据见对照矩阵 |
| G04 | github-channel | 评论/review reply/resolve、去重、review不存在回退、deleted/locked分类、PR真实发布 | Source/Execution | 已实现；验收证据见对照矩阵 |
| F01 | sources/feishu* | 事件接入、恢复历史、订阅topic轮询、chat scopes/direct/mentions、自bot过滤 | Source | 已实现；验收证据见对照矩阵 |
| F02 | sources/feishu* | message邻域/root/parent、图片下载缓存、链接、成员同步、标签、分页错误隔离 | Source | 已实现；验收证据见对照矩阵 |
| F03 | feishu-channel | topic回复、uuid幂等、撤回/不存在gone、配置不再接收ignored | Source | 已实现；验收证据见对照矩阵 |
| U01 | docs005 | Overview/Source/Trigger/Eval/Execution/Settings 六页；精确stage/counts/24h窗口 | Management | 已实现；验收证据见对照矩阵 |
| U02 | docs005 | timeline分页/邻居/search/deep links/折叠细节；stop/retry/requeue/attach/unsubscribe/dismiss | Management | 已实现；验收证据见对照矩阵 |
| U03 | docs005/006 | Sources/Eval/Execution/Prompts/Deployment设置、乐观并发、版本历史恢复、热加载 | Management/Config | 已实现；验收证据见对照矩阵 |
| U04 | app/http/api | 全部公共路由/资源、loopback Host Origin/header校验、body限制、错误码、模型工具权限 | Management | 已实现；验收证据见对照矩阵 |
| O01 | docs006 | 常驻服务、setup检查、状态健康/退出码、单实例锁、优雅关闭、restart/intentional stop | Service | 已实现；验收证据见对照矩阵 |
| O02 | docs006/007 | 机器限制动态读取、持久settings/prompts/source state、数据库版本/升级拒绝新schema、备份 | Service/Store | 已实现；验收证据见对照矩阵 |
| X01 | docs008 | 可注册Source类型：scope/config/forms/read/context/channel/policy/environment/health | Source/Service | 已实现；验收证据见对照矩阵 |

## 技术替换与实际验证

- DSH/Node 宿主替换为 Java21 Session/工具/进程管理；不是删除宿主能力。
- 原型的默认全访问 shell 改成显式启用，保留真实命令、后台任务与工作树路径。权限默认值差异必须说明。
- REST、模型和飞书事件使用本地协议 fixture；没有凭据时不得声称真实账号端到端测试通过。
- 完整验收包括：标准 JDK21 Maven全量测试、真实临时 git/worktree与无害进程测试、HTTP/UI页面检查、持续服务重启/暂停/恢复测试、独立按表复核。
