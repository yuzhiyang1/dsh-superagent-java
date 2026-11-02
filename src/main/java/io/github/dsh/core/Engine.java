package io.github.dsh.core;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import static io.github.dsh.core.Model.*;

/**
 * 唯一领域写入口。每个命令以 SQLite 事务提交；事务里不调用网络和模型。
 * 读取返回反序列化副本，调用者修改它不会绕过这里的状态机。
 */
public final class Engine {
    private final SqliteStore store;
    private final Clock clock;
    public Engine(SqliteStore store, Clock clock) { this.store = store; this.clock = clock; }
    public Engine(SqliteStore store) { this(store, Clock.systemUTC()); }
    private long now() { return clock.millis(); }
    private String id(String prefix) { return prefix + "-" + UUID.randomUUID(); }
    public Policy policy() { Policy p = store.get("policy","current",Policy.class); return p == null ? Policy.safe() : p; }
    public void setPolicy(Policy next) {
        store.atomic(() -> {
            Policy previous = policy();
            Policy historical = store.get("policyVersion",next.version(),Policy.class);
            if (historical != null && !historical.equals(next)) throw new IllegalArgumentException("Policy version was already used for different rules");
            if (previous.version().equals(next.version()) && !previous.equals(next)) throw new IllegalArgumentException("Changed policy requires a new version");
            store.save("policy","current",next);
            store.save("policyVersion",next.version(),next);
            if (!previous.equals(next)) for (Ticket ticket : tickets()) if (ticket.status == TicketStatus.OPEN) {
                addInput(ticket,null,"Policy changed to " + next.version() + "; existing mandate scope is unchanged",true);
                save(ticket);
            }
            return null;
        });
    }
    public Trigger trigger(String id) { return store.get("trigger",id,Trigger.class); }
    public Ticket ticket(String id) { return store.get("ticket",id,Ticket.class); }
    public List<Ticket> tickets() { return store.list("ticket",Ticket.class); }
    public List<Decision> decisions() { return store.list("decision",Decision.class); }
    public List<ThreadState> threads() { return store.list("thread",ThreadState.class); }
    public List<Assignment> assignments(String ticketId) { return List.copyOf(requireTicket(ticketId).assignments); }
    public List<Assignment> pendingAssignments() { return tickets().stream().filter(t -> t.status == TicketStatus.OPEN).flatMap(t -> t.assignments.stream()).filter(a -> a.status == AssignmentStatus.PENDING).toList(); }
    private Ticket requireTicket(String id) { Ticket t = ticket(id); if (t == null) throw new IllegalArgumentException("Unknown ticket: " + id); return t; }
    private Trigger requireTrigger(String id) { Trigger t = trigger(id); if (t == null) throw new IllegalArgumentException("Unknown trigger: " + id); return t; }
    private void save(Ticket ticket) { store.save("ticket",ticket.id,ticket); }
    private ThreadState thread(String key) { ThreadState t = store.get("thread",key,ThreadState.class); return t == null ? new ThreadState(key) : t; }
    private void save(ThreadState thread) { store.save("thread",thread.key,thread); }
    private Disposition disposition(String triggerId) { Disposition d = store.get("disposition",triggerId,Disposition.class); return d == null ? new Disposition(triggerId) : d; }
    private void save(Disposition disposition) { store.save("disposition",disposition.triggerId,disposition); }

    /** 一页事件和页尾游标一起提交，包括空页；上层轮询器不必访问数据库内部。 */
    public List<String> receiveBatch(List<Trigger> triggers, String source, String cursor) {
        return store.atomic(() -> {
            if (triggers.stream().anyMatch(t -> !source.equals(t.source()))) throw new IllegalArgumentException("Batch source mismatch");
            List<String> results = triggers.stream().map(this::receive).toList();
            if (cursor != null) store.cursor(source,cursor);
            return results;
        });
    }
    public String receive(Trigger trigger) { return receive(trigger,null); }
    /** cursor 和事件同一个事务：重启既不漏读，也不会因重放重复建单。 */
    public String receive(Trigger received, String cursor) {
        return store.atomic(() -> {
            Instant.parse(received.at()); // 拒绝不能排序的来源时间。
            if (trigger(received.id()) != null) {
                if (cursor != null) store.cursor(received.source(),cursor);
                return "duplicate";
            }
            store.save("trigger",received.id(),received);
            Disposition disposition = disposition(received.id());
            Map<String,Boolean> reached = new LinkedHashMap<>();
            for (ThreadRef ref : received.threads()) {
                ThreadState thread = thread(ref.key());
                for (ThreadRef other : received.threads()) if (!other.equals(ref)) thread.correlated.add(other.key());
                for (Subscription sub : thread.subscriptions) reached.merge(sub.ticketId(),sub.contextOnly(),(a,b) -> a && b);
                save(thread);
            }
            for (var entry : reached.entrySet()) {
                Ticket ticket = requireTicket(entry.getKey()); boolean context = entry.getValue();
                if (ticket.stopped) continue;
                if (ticket.status == TicketStatus.ENDED) {
                    if (!received.human() || context || !validMandate(ticket.mandate)) continue;
                    ticket.status = TicketStatus.OPEN; newAssignment(ticket);
                }
                addInput(ticket,received.id(),received.text(),context);
                if (received.human() && !context) disposition.owners.add(ticket.id);
                save(ticket);
            }
            save(disposition);
            if (disposition.owners.isEmpty()) queue(received);
            if (cursor != null) store.cursor(received.source(),cursor);
            return "stored";
        });
    }
    private void queue(Trigger trigger) {
        ThreadState thread = thread(trigger.threads().getFirst().key());
        if (!thread.pending.contains(trigger.id()) && (thread.evaluating == null || !thread.evaluating.triggerIds().contains(trigger.id()))) thread.pending.add(trigger.id());
        save(thread);
    }
    private Strength gate(Trigger trigger, boolean followup) {
        Policy policy = policy(); SourcePolicy source = policy.sources().get(trigger.source());
        if (source == null) return null;
        if (!trigger.human()) return source.allowedBots().contains(trigger.actor()) && (trigger.addressed() || trigger.receiveAll()) && policy.acceptMode() != AcceptMode.OFF ? Strength.INFERRED : null;
        boolean listed = source.allowedActors().contains(trigger.actor());
        if (listed && trigger.addressed()) return Strength.EXPLICIT;
        AdmissionRow row = source.admission().getOrDefault(trigger.scope(),AdmissionRow.safe());
        Admission cell = listed ? row.listedUnaddressed() : trigger.addressed() ? row.unlistedAddressed() : row.unlistedUnaddressed();
        if (!listed && trigger.addressed() && cell == Admission.EXPLICIT) return Strength.EXPLICIT;
        if (followup) return listed ? Strength.EXPLICIT : policy.acceptMode() != AcceptMode.OFF ? Strength.INFERRED : null;
        if (!trigger.addressed() && !trigger.receiveAll()) return null;
        return cell == Admission.INFERRED && policy.acceptMode() != AcceptMode.OFF ? Strength.INFERRED : null;
    }
    private boolean hasOwnTicket(Trigger trigger) {
        return trigger.threads().stream().map(r -> thread(r.key())).flatMap(t -> t.subscriptions.stream()).anyMatch(s -> !s.contextOnly() && !requireTicket(s.ticketId()).stopped);
    }
    /** 每个 Thread 最多一个 Eval。模型慢时新事件留在 pending，下一批再处理。 */
    public List<Evaluation> evaluateDue() {
        return store.atomic(() -> {
            List<Evaluation> active = new ArrayList<>();
            for (ThreadState thread : threads()) {
                if (thread.evaluating != null && thread.evaluating.deadline() <= now()) {
                    Evaluation old = thread.evaluating; thread.evaluating = null;
                    if (++thread.expiries <= 2) thread.pending.addAll(0,old.triggerIds());
                    else record(old,DecisionKind.UNAVAILABLE,null,"evaluation timed out");
                    save(thread);
                }
                if (thread.evaluating != null) { active.add(thread.evaluating); continue; }
                thread.pending.removeIf(triggerId -> !disposition(triggerId).owners.isEmpty() || store.get("accepted",triggerId,String.class) != null);
                if (thread.pending.isEmpty()) { save(thread); continue; }
                Trigger primary = null; Strength strength = null;
                for (String triggerId : thread.pending) {
                    Trigger candidate = requireTrigger(triggerId); Strength admitted = gate(candidate,hasOwnTicket(candidate));
                    if (admitted != null && (strength != Strength.EXPLICIT || admitted == Strength.EXPLICIT)) { primary = candidate; strength = admitted; }
                }
                Evaluation evaluation = new Evaluation(id("eval"),thread.key,List.copyOf(thread.pending),primary == null ? thread.pending.getFirst() : primary.id(),strength,policy().version(),now(),now()+120_000);
                thread.pending.clear();
                if (primary == null) record(evaluation,DecisionKind.IGNORE,null,"not admitted by policy");
                else { thread.evaluating = evaluation; active.add(evaluation); }
                save(thread);
            }
            return active;
        });
    }
    public Decision decide(String evalId, Outcome outcome) {
        return store.atomic(() -> {
            Decision earlier = store.get("evalDecision",evalId,Decision.class); if (earlier != null) return earlier;
            ThreadState thread = threads().stream().filter(t -> t.evaluating != null && t.evaluating.id().equals(evalId)).findFirst().orElseThrow(() -> new IllegalStateException("Eval no longer active"));
            Evaluation evaluation = thread.evaluating;
            DecisionKind kind = outcome.kind(); String reason = outcome.reason(); String ticketId = null;
            if (evaluation.deadline() <= now()) { kind = DecisionKind.UNAVAILABLE; reason = "evaluation deadline exceeded"; }
            else if (!evaluation.policyVersion().equals(policy().version())) { kind = DecisionKind.UNAVAILABLE; reason = "stale policy"; }
            else if (kind == DecisionKind.ACCEPT) {
                if (outcome.scope() == null || outcome.scope().isBlank() || outcome.title() == null || outcome.title().isBlank()) throw new IllegalArgumentException("Accept requires title and scope");
                if (evaluation.strength() == Strength.INFERRED) {
                    if (outcome.matchedRules().isEmpty() || !policy().enabledRules().containsAll(outcome.matchedRules())) throw new IllegalArgumentException("Inferred acceptance needs enabled matching rules");
                    if (policy().acceptMode() != AcceptMode.AUTO) { kind = DecisionKind.WOULD_ACCEPT; reason = "observe: no ticket created"; }
                }
                if (kind == DecisionKind.ACCEPT) {
                    Trigger origin = requireTrigger(evaluation.primaryTriggerId());
                    Ticket ticket = new Ticket(); ticket.id = id("ticket"); ticket.title = outcome.title(); ticket.originTriggerId = origin.id();
                    ticket.mandate = new Mandate(policy().version(),evaluation.strength(),outcome.scope(),origin.source(),origin.actor(),origin.id(),hasOwnTicket(origin),outcome.matchedRules());
                    for (String triggerId : evaluation.triggerIds()) {
                        Trigger trigger = requireTrigger(triggerId);
                        if (trigger.channel() != null && !ticket.channels.contains(trigger.channel())) ticket.channels.add(trigger.channel());
                        ticket.inputs.add(new Input(id("input"),trigger.id(),trigger.text(),false));
                        store.save("accepted",triggerId,ticket.id);
                        for (ThreadRef ref : trigger.threads()) subscribe(ticket.id,ref,false);
                    }
                    newAssignment(ticket); save(ticket); ticketId = ticket.id;
                }
            } else if (kind == DecisionKind.STOP) {
                Trigger origin = requireTrigger(evaluation.primaryTriggerId());
                Ticket target = outcome.ticketId() == null ? null : ticket(outcome.ticketId());
                if (!origin.human() || evaluation.strength() != Strength.EXPLICIT || target == null || !sharesOwnThread(target.id,origin)) { kind = DecisionKind.IGNORE; reason = "stop requires explicit human request on ticket thread"; }
                else { stop(target); ticketId = target.id; }
            } else if (kind == DecisionKind.WOULD_ACCEPT) throw new IllegalArgumentException("WOULD_ACCEPT is an engine result, not an assessor command");
            // subscribe() may have saved this same Thread, so reload before clearing its Eval.
            thread = thread(thread.key); thread.evaluating = null; thread.expiries = 0;
            if ("stale policy".equals(reason)) {
                // 旧策略的结论作废，但请求本身没有消失：重新交给当前策略。
                for (String triggerId : evaluation.triggerIds()) if (!thread.pending.contains(triggerId)) thread.pending.add(triggerId);
            }
            save(thread);
            return record(evaluation,kind,ticketId,reason);
        });
    }
    private Decision record(Evaluation evaluation, DecisionKind kind, String ticketId, String reason) {
        Decision decision = new Decision(id("decision"),evaluation.id(),kind,ticketId,reason,evaluation.policyVersion(),now());
        store.save("decision",decision.id(),decision); store.save("evalDecision",evaluation.id(),decision); return decision;
    }
    private boolean validMandate(Mandate mandate) {
        Trigger origin = requireTrigger(mandate.originTriggerId()); Strength current = gate(origin,mandate.followupBasis());
        if (current == null || (mandate.strength() == Strength.EXPLICIT && current != Strength.EXPLICIT)) return false;
        return mandate.strength() != Strength.INFERRED || (policy().acceptMode() == AcceptMode.AUTO && policy().enabledRules().containsAll(mandate.matchedRules()));
    }
    private void newAssignment(Ticket ticket) {
        Assignment assignment = new Assignment(id("assignment"),ticket.id,now()); ticket.assignments.add(assignment);
        store.enqueue(EffectKind.START,assignment.id,Map.of("ticketId",ticket.id,"assignmentId",assignment.id),now());
    }
    private void addInput(Ticket ticket, String triggerId, String text, boolean contextOnly) {
        Input input = new Input(id("input"),triggerId,text,contextOnly); ticket.inputs.add(input);
        Assignment assignment = ticket.latestAssignment();
        if (assignment != null && assignment.status != AssignmentStatus.ENDED)
            store.enqueue(EffectKind.INPUT,input.id,Map.of("ticketId",ticket.id,"assignmentId",assignment.id,"inputId",input.id),now());
    }
    private void subscribe(String ticketId, ThreadRef ref, boolean contextOnly) {
        ThreadState thread = thread(ref.key()); Subscription prior = thread.subscriptions.stream().filter(s -> s.ticketId().equals(ticketId)).findFirst().orElse(null);
        if (prior == null) thread.subscriptions.add(new Subscription(ticketId,contextOnly));
        else if (prior.contextOnly() && !contextOnly) { thread.subscriptions.remove(prior); thread.subscriptions.add(new Subscription(ticketId,false)); }
        save(thread);
    }
    public void followThread(String ticketId, ThreadRef ref, boolean contextOnly) {
        store.atomic(() -> { Ticket t = requireTicket(ticketId); if (t.status != TicketStatus.OPEN) throw new IllegalStateException("Ticket ended"); subscribe(ticketId,ref,contextOnly); return null; });
    }
    private boolean sharesOwnThread(String ticketId, Trigger trigger) {
        return trigger.threads().stream().map(r -> thread(r.key())).flatMap(t -> t.subscriptions.stream()).anyMatch(s -> s.ticketId().equals(ticketId) && !s.contextOnly());
    }
    public void assignmentStarted(String ticketId, String assignmentId) {
        store.atomic(() -> { Ticket ticket = requireTicket(ticketId); Assignment assignment = current(ticket,assignmentId); if (assignment.status == AssignmentStatus.PENDING) assignment.status = AssignmentStatus.RUNNING; save(ticket); return null; });
    }
    private Assignment current(Ticket ticket, String assignmentId) {
        Assignment current = ticket.latestAssignment();
        if (ticket.status != TicketStatus.OPEN || current == null || !current.id.equals(assignmentId) || current.status == AssignmentStatus.ENDED) throw new IllegalStateException("Stale or ended assignment");
        return current;
    }
    /** 运行时标记整个快照输入已交给执行器；适用于不带 triggerId 的 Policy 通知。 */
    public void acknowledgeInput(String ticketId, String inputId) {
        store.atomic(() -> {
            Ticket ticket = requireTicket(ticketId);
            if (ticket.status != TicketStatus.OPEN) throw new IllegalStateException("Ticket ended");
            Input input = ticket.inputs.stream().filter(i -> i.id.equals(inputId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown ticket input"));
            input.acknowledged = true; save(ticket); return null;
        });
    }
    public void acknowledge(String ticketId, String triggerId) {
        store.atomic(() -> { Ticket ticket = requireTicket(ticketId); if (ticket.status != TicketStatus.OPEN) throw new IllegalStateException("Ticket ended");
            boolean found = false; for (Input input : ticket.inputs) if (Objects.equals(input.triggerId,triggerId)) { input.acknowledged = true; found = true; }
            if (!found) throw new IllegalArgumentException("Trigger never reached ticket"); save(ticket); return null; });
    }
    /** 只有当前 Assignment 的报告能驱动工单；执行器进程消失不等于业务工单完成。 */
    public void report(String ticketId, String assignmentId, Report report) {
        store.atomic(() -> {
            Ticket ticket = requireTicket(ticketId); Assignment assignment = current(ticket,assignmentId); assignment.report = report;
            switch (report.kind()) {
                case READY, DROPPED -> { assignment.status = AssignmentStatus.ENDED; ticket.status = TicketStatus.ENDED; releaseUnread(ticket); }
                case FAILED, BLOCKED -> assignment.status = AssignmentStatus.ENDED;
                case PROGRESS, WAITING -> assignment.status = AssignmentStatus.RUNNING;
            }
            save(ticket); return null;
        });
    }
    private void releaseUnread(Ticket ticket) {
        for (Input input : ticket.inputs) if (input.triggerId != null) {
            Disposition disposition = disposition(input.triggerId);
            if (disposition.owners.remove(ticket.id)) {
                save(disposition);
                if (!input.acknowledged && disposition.owners.isEmpty()) queue(requireTrigger(input.triggerId));
            }
        }
    }
    public boolean handback(String ticketId, String triggerId, String reason) {
        return store.atomic(() -> {
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Handback needs a reason");
            requireTicket(ticketId); Disposition disposition = disposition(triggerId);
            if (!disposition.owners.remove(ticketId) || disposition.handbacks.containsKey(ticketId)) return false;
            disposition.handbacks.put(ticketId,reason); save(disposition);
            if (disposition.owners.isEmpty()) { queue(requireTrigger(triggerId)); return true; } return false;
        });
    }
    public Ticket retryTicket(String ticketId) {
        return store.atomic(() -> {
            Ticket ticket = requireTicket(ticketId); Assignment previous = ticket.latestAssignment();
            if (ticket.status != TicketStatus.OPEN || ticket.stopped || previous == null || previous.report == null || !Set.of(ReportKind.FAILED,ReportKind.BLOCKED).contains(previous.report.kind())) throw new IllegalStateException("Ticket is not retryable");
            if (!validMandate(ticket.mandate)) throw new IllegalStateException("Mandate no longer valid");
            previous.status = AssignmentStatus.ENDED; newAssignment(ticket); save(ticket); return ticket;
        });
    }
    /** 人工管理入口。不可把模型自由文本当成调用本方法的授权。 */
    public Ticket stopTicket(String ticketId) { return store.atomic(() -> { Ticket ticket = requireTicket(ticketId); stop(ticket); return ticket; }); }
    private void stop(Ticket ticket) {
        ticket.stopped = true; ticket.status = TicketStatus.ENDED;
        Assignment assignment = ticket.latestAssignment();
        if (assignment != null && assignment.status != AssignmentStatus.ENDED) {
            assignment.status = AssignmentStatus.ENDED;
            store.enqueue(EffectKind.STOP,assignment.id,Map.of("ticketId",ticket.id,"assignmentId",assignment.id),now());
        }
        // 停止不把停止消息重送成新任务。其他 Ticket 的所有权保持不变。
        for (Input input : ticket.inputs) if (input.triggerId != null) { Disposition d = disposition(input.triggerId); d.owners.remove(ticket.id); save(d); }
        save(ticket);
    }
    /** 已被工单拥有的 follow-up 不走普通 Eval，但明确的人工停止仍可以生效。 */
    public void stopFromTrigger(String ticketId, String triggerId) {
        store.atomic(() -> {
            Trigger trigger = requireTrigger(triggerId);
            if (!trigger.human() || gate(trigger,hasOwnTicket(trigger)) != Strength.EXPLICIT || !sharesOwnThread(ticketId,trigger)) throw new IllegalStateException("Stop requires explicit authority on ticket thread");
            stop(requireTicket(ticketId)); return null;
        });
    }
    /** 路由器明确确认消息指向此工单后调用；不是 Executor 可自由使用的扩权工具。 */
    public Ticket attach(String ticketId, String triggerId, String note) {
        return store.atomic(() -> {
            Ticket ticket = requireTicket(ticketId); Trigger trigger = requireTrigger(triggerId);
            Disposition disposition = disposition(triggerId);
            if (ticket.stopped || !validMandate(ticket.mandate)) throw new IllegalStateException("Cannot attach under withdrawn or revoked mandate");
            if (disposition.handbacks.containsKey(ticketId)) throw new IllegalStateException("Ticket already handed this trigger back");
            if (ticket.status == TicketStatus.ENDED) { ticket.status = TicketStatus.OPEN; newAssignment(ticket); }
            for (ThreadRef ref : trigger.threads()) subscribe(ticket.id,ref,false);
            if (trigger.channel() != null && !ticket.channels.contains(trigger.channel())) ticket.channels.add(trigger.channel());
            addInput(ticket,trigger.id(),trigger.text() + (note == null ? "" : "\nRouting note: " + note),false);
            if (trigger.human()) disposition.owners.add(ticket.id);
            save(disposition); save(ticket);
            // 被附加工单的消息不再由 Eval 决定；同批其他消息重新排队。
            ThreadState thread = thread(trigger.threads().getFirst().key());
            thread.pending.remove(triggerId);
            if (thread.evaluating != null && thread.evaluating.triggerIds().contains(triggerId)) {
                for (String other : thread.evaluating.triggerIds()) if (!other.equals(triggerId) && !thread.pending.contains(other)) thread.pending.add(other);
                record(thread.evaluating,DecisionKind.IGNORE,ticketId,"routed to existing ticket"); thread.evaluating = null;
            }
            save(thread); return ticket;
        });
    }
    /** 面板人工操作：取消订阅不撤销已经收到的 follow-up 所有权。 */
    public boolean unsubscribe(ThreadRef ref) {
        return store.atomic(() -> { ThreadState thread = thread(ref.key()); boolean had = !thread.subscriptions.isEmpty(); thread.subscriptions.clear(); save(thread); return had; });
    }
    /** 人工重评不可偷走 Executor 正在负责的消息；须先 handback。 */
    public boolean reevaluate(String triggerId) {
        return store.atomic(() -> {
            Trigger trigger = requireTrigger(triggerId);
            if (!disposition(triggerId).owners.isEmpty()) throw new IllegalStateException("Follow-up still belongs to an executor");
            ThreadState thread = thread(trigger.threads().getFirst().key());
            if (thread.evaluating != null && thread.evaluating.triggerIds().contains(triggerId)) return false;
            if (store.get("accepted",triggerId,String.class) != null) return false;
            queue(trigger); return true;
        });
    }
    public Disposition triggerDisposition(String triggerId) { requireTrigger(triggerId); return disposition(triggerId); }
    public boolean queueReply(String messageId, String ticketId, Channel channel, String text) {
        return store.atomic(() -> {
            Ticket ticket = requireTicket(ticketId);
            if (!ticket.channels.contains(channel)) throw new IllegalArgumentException("Channel is outside ticket");
            if (outboxMessage(messageId) != null) return false;
            store.save("outbox",messageId,new OutboxMessage(messageId,ticketId,channel,text,"pending",null));
            store.enqueue(EffectKind.REPLY,messageId,Map.of("messageId",messageId),now()); return true;
        });
    }
    public OutboxMessage outboxMessage(String messageId) { return store.get("outbox",messageId,OutboxMessage.class); }
    public void markReplySent(String messageId, String receipt) {
        store.atomic(() -> { OutboxMessage message = outboxMessage(messageId); if (message == null) throw new IllegalArgumentException("Unknown outbox message");
            store.save("outbox",messageId,new OutboxMessage(message.id(),message.ticketId(),message.channel(),message.text(),"sent",receipt)); return null; });
    }
}
