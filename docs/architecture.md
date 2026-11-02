# 从 Java 后端视角理解

## 1. 请求不等于授权

`Trigger.text` 来自外部，永远是不可信上下文。`actor/human/addressed` 由 Source 的可信 API 元数据提供。白名单、准入表与规则在 Policy，模型无权修改。

- `EXPLICIT`：允许的人明确 @ 或基于已有工作跟进。
- `INFERRED`：只可能在 AUTO 且命中启用规则后创建 Ticket。OBSERVE 记录但不执行，OFF 不准入推断候选。
- `Mandate` 固化“为何允许、允许做什么、依据哪个版本”。消息扇出是上下文传播，不是给执行器额外权限。

## 2. 三个不同状态机

Thread 同时最多一个 Eval；Eval 期间新 Trigger 进入下一批。Decision 迟到或策略过期不能偷偷创建新工作。

Ticket `OPEN → ENDED`；未人工停止的 Ticket 可以由自身订阅线程的人的消息恢复。恢复需重验原授权，context-only 线程和 bot 不唤醒已结束任务。人工 stop 撤回授权，不能被普通消息恢复。

Assignment `PENDING → RUNNING → ENDED`。FAILED/BLOCKED 结束派工但保留 OPEN Ticket，可显式 retry；旧 Assignment 迟到报告无效。READY/DROPPED 在此学习版结束 Ticket。WAITING 使用结构化 question，而不是从摘要猜测。

## 3. 为什么有 Effect + Outbox 两层？

```text
Engine 命令
  └─ SQLite transaction
      ├─ 保存 Ticket/Assignment
      └─ 保存 effect
commit
  └─ Runtime claim effect（租约）
      ├─ 调执行器 / 调渠道（事务外）
      └─ 回写 report / receipt
```

Effect 是“要做的事”；Outbox 是“某消息是否已经发送及其回执”。崩溃不丢已提交意图；失败保存 attempts/due_at，指数退避至上限；进程重启可以回收过期租约。只在确定旧进程已停止时调用 `recoverEffects()` 立即回收，否则等待租约到期。

外部 API 和 SQLite 不能共用事务。因此系统是 **at-least-once**，不是宣称 exactly-once：GitHub 用稳定 marker 查询缓解重复，飞书用 uuid；执行器重新执行须幂等或可安全重做。仅支持一个运行实例。

## 4. Follow-up 不重新抢工单

订阅消息先扇出；人的非 context-only 消息由现有 Ticket 拥有，不进入新 Eval。执行器 `acknowledge` 表示读到，`handback` 表示范围之外。所有 owner 都释放后才能再评估。attach 要求由明确的路由操作调用，不能让模型随意扩大原 Mandate。

## 5. 测试怎样读

先看 EngineTest 的授权和状态转换，再看 RuntimeTest 的失败恢复、回执和事务回滚，最后看 AdaptersTest 的真实 HTTP 协议 fixture。测试不连接真实 GitHub、飞书或模型服务；真实外部集成需操作人另外配置环境变量与授权。
