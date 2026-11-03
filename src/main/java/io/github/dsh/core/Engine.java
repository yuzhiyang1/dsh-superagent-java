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
    public ProcessingSettings processing() {
        ProcessingSettings settings = store.get("processing","current",ProcessingSettings.class);
        return settings == null ? new ProcessingSettings(false,120_000) : settings;
    }
    public void setProcessing(boolean paused,long timeoutMs) {
        store.atomic(() -> {
            ProcessingSettings next = new ProcessingSettings(paused,timeoutMs);
            store.save("processing","current",next);
            for (ThreadState thread : threads()) if (thread.evaluating != null) {
                Evaluation e=thread.evaluating;
                Long pausedAt=e.pausedAt(); long deadline=e.deadline();
                if (paused && pausedAt == null) pausedAt=now();
                if (!paused && pausedAt != null) { deadline += now()-pausedAt; pausedAt=null; }
                thread.evaluating=new Evaluation(e.id(),e.threadKey(),e.triggerIds(),e.primaryTriggerId(),e.strength(),e.policyVersion(),e.createdAt(),deadline,e.deliveredAt(),pausedAt,e.authority());
                save(thread);
            }
            emit("processing.changed",Map.of("paused",Boolean.toString(paused))); return null;
        });
    }
    private void emit(String type,Map<String,String> payload) { store.appendEvent(type,payload,now()); }
    public List<DomainEvent> events(long afterSequence,int limit) { return store.events(afterSequence,limit); }
    public void setPolicy(Policy next) {
        store.atomic(() -> {
            Policy previous = policy();
            Policy historical = store.get("policyVersion",next.version(),Policy.class);
            if (historical != null && !historical.equals(next)) throw new IllegalArgumentException("Policy version was already used for different rules");
            if (previous.version().equals(next.version()) && !previous.equals(next)) throw new IllegalArgumentException("Changed policy requires a new version");
            Set<String> heldBefore=new HashSet<>(); for (Ticket ticket : tickets()) if (validMandate(ticket.mandate)) heldBefore.add(ticket.id);
            store.save("policy","current",next);
            store.save("policyVersion",next.version(),next);
            if (!previous.equals(next)) {
                for (Ticket ticket : tickets()) if (ticket.status == TicketStatus.OPEN) {
                    boolean revoked=heldBefore.contains(ticket.id) && !validMandate(ticket.mandate);
                    List<String> changedRules=ticket.mandate.matchedRules().stream().filter(rule ->
                        previous.enabledRules().contains(rule) != next.enabledRules().contains(rule)
                        || !Objects.equals(previous.ruleInstructions().get(rule),next.ruleInstructions().get(rule))).toList();
                    if (revoked || !changedRules.isEmpty()) {
                        addInput(ticket,null,"Policy changed from " + previous.version()+" to "+next.version()+"; mandate now valid="+!revoked+"; affected rules="+changedRules+"; scope is unchanged",true,InputKind.POLICY_CHANGED);
                        save(ticket);
                    }
                }
                for (ThreadState thread : threads()) if (thread.evaluating != null) reassess(thread,"stale policy",Set.of());
                emit("policy.changed",Map.of("from",previous.version(),"to",next.version()));
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
    public String receive(Trigger incoming, String cursor) {
        String canonicalAt=new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.parse(incoming.at()));
        Trigger received=new Trigger(incoming.id(),incoming.source(),incoming.threads(),incoming.actor(),incoming.human(),incoming.addressed(),incoming.receiveAll(),incoming.scope(),canonicalAt,incoming.text(),incoming.channel(),incoming.metadata(),incoming.url(),incoming.replyTo());
        return store.atomic(() -> {
            Instant.parse(received.at()); // 拒绝不能排序的来源时间。
            if (trigger(received.id()) != null) {
                if (cursor != null) store.cursor(received.source(),cursor);
                return "duplicate";
            }
            store.save("trigger",received.id(),received);
            if (!listened(received) || selfMessage(received)) {
                Disposition filtered=disposition(received.id()); filtered.filtered=selfMessage(received) ? "selfMessage" : "outsideScope"; save(filtered);
                if (cursor != null) store.cursor(received.source(),cursor);
                emit("trigger.filtered",Map.of("triggerId",received.id(),"reason",filtered.filtered)); return "filtered";
            }
            Disposition disposition = disposition(received.id());
            Map<String,Boolean> reached = new LinkedHashMap<>();
            for (ThreadRef ref : received.threads()) {
                ThreadState thread = thread(ref.key());
                if (thread.createdAt == 0) thread.createdAt=now(); thread.lastAt=Math.max(thread.lastAt,Instant.parse(received.at()).toEpochMilli());
                for (ThreadRef other : received.threads()) if (!other.equals(ref)) thread.correlated.add(other.key());
                for (Subscription sub : thread.subscriptions) reached.merge(sub.ticketId(),sub.contextOnly(),(a,b) -> a && b);
                save(thread);
            }
            for (var entry : reached.entrySet()) {
                Ticket ticket = requireTicket(entry.getKey()); boolean context = entry.getValue();
                if (ticket.stopped) continue;
                if (ticket.status == TicketStatus.ENDED) {
                    if (!received.human() || context) continue;
                }
                addInput(ticket,received.id(),received.text(),context);
                disposition.fanout.add(ticket.id);
                if (context) disposition.context.add(ticket.id);
                if (received.human() && !context) disposition.owners.add(ticket.id);
                save(ticket);
            }
            save(disposition);
            for (String ticketId : reached.keySet()) wake(ticketId);
            if (owners(received.id()).isEmpty()) queue(received);
            emit("trigger.received",Map.of("triggerId",received.id()));
            if (received.human() && !owners(received.id()).isEmpty()) emit("trigger.followup",Map.of("triggerId",received.id(),"ticketIds",String.join(",",owners(received.id()))));
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
        if (source == null || !listened(trigger)) return null;
        if (!trigger.human()) {
            if (!(trigger.addressed() || trigger.receiveAll()) || policy.acceptMode() == AcceptMode.OFF) return null;
            if (trigger.actor().isEmpty()) return source.admission().getOrDefault(trigger.scope(),AdmissionRow.safe()).unlistedUnaddressed() == Admission.INFERRED ? Strength.INFERRED : null;
            return listed(source,source.allowedBots(),trigger.actor()) ? Strength.INFERRED : null;
        }
        boolean listed = listed(source,source.allowedActors(),trigger.actor());
        if (listed && trigger.addressed()) return Strength.EXPLICIT;
        AdmissionRow row = source.admission().getOrDefault(trigger.scope(),AdmissionRow.safe());
        Admission cell = listed ? row.listedUnaddressed() : trigger.addressed() ? row.unlistedAddressed() : row.unlistedUnaddressed();
        if (!listed && trigger.addressed() && cell == Admission.EXPLICIT) return Strength.EXPLICIT;
        if (followup && !(Boolean.TRUE.equals(trigger.metadata().get("direct")) && !listed)) return listed ? Strength.EXPLICIT : policy.acceptMode() != AcceptMode.OFF ? Strength.INFERRED : null;
        if (!trigger.addressed() && !trigger.receiveAll()) return null;
        return cell == Admission.INFERRED && policy.acceptMode() != AcceptMode.OFF ? Strength.INFERRED : null;
    }
    private boolean hasOwnTicket(Trigger trigger) {
        return trigger.threads().stream().map(r -> thread(r.key())).flatMap(t -> t.subscriptions.stream()).anyMatch(s -> !s.contextOnly());
    }
    /** 每个 Thread 最多一个 Eval。模型慢时新事件留在 pending，下一批再处理。 */
    public List<Evaluation> evaluateDue() {
        return store.atomic(() -> {
            if (processing().paused()) return List.of();
            for (Ticket ticket : tickets()) if (ticket.status == TicketStatus.ENDED && !ticket.undelivered.isEmpty()) wake(ticket.id);
            List<Evaluation> active = new ArrayList<>();
            for (ThreadState thread : threads()) {
                if (thread.evaluating != null && thread.evaluating.deliveredAt() != null && thread.evaluating.deadline() <= now()) {
                    Evaluation old = thread.evaluating; thread.evaluating = null;
                    if (++thread.expiries <= 2) thread.pending.addAll(0,old.triggerIds());
                    else record(old,DecisionKind.UNAVAILABLE,null,"evaluation timed out");
                    save(thread);
                }
                if (thread.evaluating != null) { active.add(thread.evaluating); continue; }
                thread.pending.removeIf(triggerId -> !owners(triggerId).isEmpty());
                List<String> alreadyStarted=thread.pending.stream().filter(triggerId -> store.get("accepted",triggerId,String.class) != null).toList();
                if (!alreadyStarted.isEmpty()) {
                    Evaluation ignored=new Evaluation(id("eval-replayed"),thread.key,alreadyStarted,alreadyStarted.getFirst(),Strength.INFERRED,policy().version(),now(),now());
                    record(ignored,DecisionKind.IGNORE,null,"already started ticket"); thread.pending.removeAll(alreadyStarted);
                }
                if (thread.pending.isEmpty()) { save(thread); continue; }
                Trigger primary = null; Strength strength = null;
                for (String triggerId : thread.pending) {
                    Trigger candidate = requireTrigger(triggerId); Strength admitted = gate(candidate,hasOwnTicket(candidate));
                    if (admitted != null && (strength == null || admitted == Strength.EXPLICIT && strength != Strength.EXPLICIT
                            || admitted == strength && Instant.parse(candidate.at()).compareTo(Instant.parse(primary.at())) >= 0)) { primary = candidate; strength = admitted; }
                }
                Evaluation evaluation = new Evaluation(id("eval"),thread.key,List.copyOf(thread.pending),primary == null ? thread.pending.getFirst() : primary.id(),strength,policy().version(),now(),now()+processing().evalTimeoutMs(),null,null,primary == null ? null : mandate(primary,strength,null,Set.of()));
                thread.pending.clear();
                if (primary == null) record(evaluation,DecisionKind.IGNORE,null,"not admitted by policy");
                else {
                    thread.evaluating = evaluation; active.add(evaluation);
                    for (String triggerId : evaluation.triggerIds()) { Disposition d=disposition(triggerId); d.evalId=evaluation.id(); save(d); }
                    emit("eval.started",Map.of("evalId",evaluation.id(),"threadKey",thread.key));
                }
                save(thread);
            }
            return active;
        });
    }
    /** 路由器按 Trigger 直接决策，不占用同一 Thread 上已有的其他 Eval。 */
    public Decision decideTrigger(String triggerId,Outcome outcome) {
        return store.atomic(() -> {
            Trigger trigger=requireTrigger(triggerId);
            if (outcome.kind() == DecisionKind.ACCEPT) {
                String accepted=store.get("accepted",triggerId,String.class);
                if (accepted != null) return decisions().stream().filter(d -> d.kind() == DecisionKind.ACCEPT && accepted.equals(d.ticketId())).findFirst().orElseThrow();
            }
            if (outcome.kind() != DecisionKind.STOP && !owners(triggerId).isEmpty()) throw new IllegalStateException("Follow-up belongs to executor");
            ThreadState thread=thread(trigger.threads().getFirst().key());
            if (thread.evaluating != null && thread.evaluating.triggerIds().contains(triggerId)) return decide(thread.evaluating.id(),outcome);
            Strength strength=gate(trigger,hasOwnTicket(trigger)); if (strength == null) throw new IllegalArgumentException("Trigger is not admissible");
            Evaluation evaluation=new Evaluation(id("eval-direct"),thread.key,List.of(triggerId),triggerId,strength,policy().version(),now(),now(),null,null,mandate(trigger,strength,null,Set.of()));
            thread.pending.remove(triggerId); save(thread); store.save("directEval",evaluation.id(),evaluation);
            return decide(evaluation.id(),outcome);
        });
    }
    public Decision decide(String evalId, Outcome outcome) {
        return store.atomic(() -> {
            Decision earlier = store.get("evalDecision",evalId,Decision.class); if (earlier != null) return earlier;
            ThreadState thread = threads().stream().filter(t -> t.evaluating != null && t.evaluating.id().equals(evalId)).findFirst().orElse(null);
            Evaluation evaluation = thread == null ? store.get("directEval",evalId,Evaluation.class) : thread.evaluating;
            if (evaluation == null) throw new IllegalStateException("Eval no longer active");
            boolean direct=thread == null;
            if (direct) thread=thread(evaluation.threadKey());
            if (outcome.kind() != DecisionKind.STOP && evaluation.triggerIds().stream().anyMatch(t -> !owners(t).isEmpty())) throw new IllegalStateException("Follow-up belongs to executor");
            DecisionKind kind = outcome.kind(); String reason = outcome.reason(); String ticketId = null;
            if (evaluation.deliveredAt() != null && !processing().paused() && evaluation.deadline() <= now()) { kind = DecisionKind.UNAVAILABLE; reason = "evaluation deadline exceeded"; }
            else if (!evaluation.policyVersion().equals(policy().version())) { kind = DecisionKind.UNAVAILABLE; reason = "stale policy"; }
            else if (kind == DecisionKind.ACCEPT) {
                if (outcome.scope() == null || outcome.scope().isBlank() || outcome.title() == null || outcome.title().isBlank()) throw new IllegalArgumentException("Accept requires title and scope");
                if (evaluation.strength() == Strength.INFERRED) {
                    if (outcome.matchedRules().isEmpty() || !policy().enabledRules().containsAll(outcome.matchedRules())) throw new IllegalArgumentException("Inferred acceptance needs enabled matching rules");
                    if (policy().acceptMode() != AcceptMode.AUTO) throw new IllegalStateException("Inferred acceptance requires AUTO; OBSERVE must record an ignore decision with assessment audit");
                }
                if (kind == DecisionKind.ACCEPT) {
                    Trigger origin = requireTrigger(evaluation.primaryTriggerId());
                    Ticket ticket = new Ticket(); ticket.id = id("ticket"); ticket.title = outcome.title(); ticket.originTriggerId = origin.id();
                    ticket.createdAt=now(); ticket.updatedAt=now(); ticket.shortName=outcome.shortName() == null || outcome.shortName().isBlank() ? ticket.id.substring(7,15) : outcome.shortName();
                    if (!ticket.shortName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) throw new IllegalArgumentException("Invalid worktree name");
                    ticket.branch="superagent/"+ticket.shortName;
                    Mandate authority=evaluation.authority() == null ? mandate(origin,evaluation.strength(),null,Set.of()) : evaluation.authority();
                    ticket.mandate = new Mandate(evaluation.policyVersion(),evaluation.strength(),outcome.scope(),origin.source(),origin.actor(),origin.id(),authority.followupBasis(),outcome.matchedRules(),authority.basis(),authority.admissionCell());
                    ticket.originTriggerIds.addAll(evaluation.triggerIds());
                    for (String triggerId : evaluation.triggerIds()) {
                        Trigger trigger = requireTrigger(triggerId);
                        if (trigger.channel() != null && !ticket.channels.contains(trigger.channel())) ticket.channels.add(trigger.channel());
                        Input input = input(trigger.id(),trigger.text(),false,InputKind.TRIGGER);
                        ticket.inputs.add(input); ticket.undelivered.add(input.id);
                        store.save("accepted",triggerId,ticket.id);
                        for (ThreadRef ref : trigger.threads()) subscribe(ticket.id,ref,false);
                    }
                    if (outcome.note() != null) addInput(ticket,null,outcome.note(),true,InputKind.NOTE);
                    newAssignment(ticket); save(ticket); ticketId = ticket.id;
                    if (outcome.acknowledgement() != null && origin.human() && origin.channel() != null)
                        queueReply("ack:"+origin.id(),ticket.id,origin.channel(),outcome.acknowledgement());
                    emit("ticket.created",Map.of("ticketId",ticket.id));
                }
            } else if (kind == DecisionKind.STOP) {
                Trigger origin = requireTrigger(evaluation.primaryTriggerId());
                Ticket target = outcome.ticketId() == null ? null : ticket(outcome.ticketId());
                if (!origin.human() || evaluation.strength() != Strength.EXPLICIT || target == null || target.status != TicketStatus.OPEN) { kind = DecisionKind.IGNORE; reason = "stop requires explicit human request on ticket thread"; }
                else { end(target,"human","stopped",now(),outcome.reason(),origin.id()); ticketId = target.id; }
            } else if (kind == DecisionKind.WOULD_ACCEPT) throw new IllegalArgumentException("WOULD_ACCEPT is an engine result, not an assessor command");
            // subscribe() may have saved this same Thread, so reload before clearing its Eval.
            thread = thread(thread.key);
            if (!direct) { thread.evaluating = null; thread.expiries = 0; }
            if ("stale policy".equals(reason)) {
                // 旧策略的结论作废，但请求本身没有消失：重新交给当前策略。
                for (String triggerId : evaluation.triggerIds()) if (!thread.pending.contains(triggerId)) thread.pending.add(triggerId);
            }
            save(thread);
            return record(evaluation,kind,ticketId,reason,outcome.audit());
        });
    }
    private Decision record(Evaluation evaluation,DecisionKind kind,String ticketId,String reason) { return record(evaluation,kind,ticketId,reason,Map.of()); }
    private Decision record(Evaluation evaluation, DecisionKind kind, String ticketId, String reason,Map<String,Object> audit) {
        Decision decision = new Decision(id("decision"),evaluation.id(),kind,ticketId,reason,evaluation.policyVersion(),now(),evaluation.threadKey(),evaluation.triggerIds(),evaluation.strength(),false,audit);
        store.save("decision",decision.id(),decision); store.save("evalDecision",evaluation.id(),decision);
        for (String triggerId : evaluation.triggerIds()) { Disposition d=disposition(triggerId); d.evalId=evaluation.id(); d.decisionId=decision.id(); save(d); }
        emit("decision.recorded",Map.of("decisionId",decision.id(),"kind",kind.name(),"threadKey",evaluation.threadKey())); return decision;
    }
    private Mandate mandate(Trigger origin,Strength strength,String scope,Set<String> rules) {
        SourcePolicy source=policy().sources().get(origin.source()); boolean listed=source != null && listed(source,source.allowedActors(),origin.actor());
        String cell=listed ? "listedUnaddressed" : origin.addressed() ? "unlistedAddressed" : "unlistedUnaddressed";
        Admission admission=admission(origin,cell);
        String basis=!origin.human() && !origin.actor().isEmpty() ? "bot" : listed && origin.addressed() ? "addressed"
            : admission == Admission.EXPLICIT ? "admission" : hasOwnTicket(origin) && origin.human() ? "followup" : "admission";
        return new Mandate(policy().version(),strength,scope,origin.source(),origin.actor(),origin.id(),basis.equals("followup"),rules,basis,cell);
    }
    private Admission admission(Trigger trigger,String cell) {
        SourcePolicy source=policy().sources().get(trigger.source()); if (source == null) return Admission.IGNORE;
        AdmissionRow row=source.admission().getOrDefault(trigger.scope(),AdmissionRow.safe());
        return switch (cell) { case "listedUnaddressed" -> row.listedUnaddressed(); case "unlistedAddressed" -> row.unlistedAddressed(); default -> row.unlistedUnaddressed(); };
    }
    private boolean validMandate(Mandate mandate) {
        SourcePolicy source=policy().sources().get(mandate.source()); if (source == null || !listened(requireTrigger(mandate.originTriggerId()))) return false;
        boolean rules=policy().acceptMode() == AcceptMode.AUTO && policy().enabledRules().containsAll(mandate.matchedRules());
        String basis=mandate.basis();
        if ("followup".equals(basis) || mandate.followupBasis()) return mandate.strength() == Strength.EXPLICIT || rules;
        Trigger origin=requireTrigger(mandate.originTriggerId()); boolean listed=listed(source,source.allowedActors(),mandate.actor());
        if ("bot".equals(basis)) return listed(source,source.allowedBots(),mandate.actor()) && rules;
        if ("admission".equals(basis)) {
            String cell=mandate.admissionCell();
            if ("unlistedAddressed".equals(cell) && listed) return true;
            if ("unlistedUnaddressed".equals(cell) && listed) cell="listedUnaddressed";
            if ("listedUnaddressed".equals(cell) && !listed) return false;
            Admission current=admission(origin,cell);
            if (current == Admission.IGNORE || mandate.strength() == Strength.EXPLICIT && current != Admission.EXPLICIT) return false;
            return mandate.strength() == Strength.EXPLICIT || rules;
        }
        if (basis != null) return listed && (mandate.strength() == Strength.EXPLICIT || rules);
        Strength current=gate(origin,mandate.followupBasis());
        return current != null && (mandate.strength() != Strength.EXPLICIT || current == Strength.EXPLICIT)
            && (mandate.strength() != Strength.INFERRED || rules);
    }
    private void newAssignment(Ticket ticket) {
        Assignment assignment = new Assignment(id("assignment"),ticket.id,now()); ticket.assignments.add(assignment); ticket.delivery=null;
        store.enqueue(EffectKind.START,assignment.id,Map.of("ticketId",ticket.id,"assignmentId",assignment.id),now());
        emit("assignment.created",Map.of("ticketId",ticket.id,"assignmentId",assignment.id));
    }
    private Input input(String triggerId,String text,boolean contextOnly,InputKind kind) {
        Input input=new Input(id("input"),triggerId,text,contextOnly); input.kind=kind; input.at=now();
        input.human=triggerId != null && requireTrigger(triggerId).human(); return input;
    }
    private void addInput(Ticket ticket,String triggerId,String text,boolean contextOnly) {
        addInput(ticket,triggerId,text,contextOnly,triggerId == null ? InputKind.NOTE : InputKind.TRIGGER);
    }
    private void addInput(Ticket ticket,String triggerId,String text,boolean contextOnly,InputKind kind) {
        Input input=input(triggerId,text,contextOnly,kind); ticket.inputs.add(input); ticket.updatedAt=now();
        input.held=ticket.status == TicketStatus.OPEN && automatic(input) && waitsForPerson(ticket);
        if (!input.held) ticket.undelivered.add(input.id);
        Assignment assignment=ticket.latestAssignment();
        if (ticket.status == TicketStatus.OPEN && !input.held && assignment != null) {
            if (assignment.status == AssignmentStatus.RUNNING)
                store.enqueue(EffectKind.INPUT,input.id,Map.of("ticketId",ticket.id,"assignmentId",assignment.id,"inputId",input.id),now());
            else if (assignment.status == AssignmentStatus.PENDING && kind != InputKind.POLICY_CHANGED && !waitsForPerson(ticket))
                store.enqueue(EffectKind.START,assignment.id,Map.of("ticketId",ticket.id,"assignmentId",assignment.id),now());
        }
    }
    private boolean automatic(Input input) { return input.kind == InputKind.NOTE || input.kind == InputKind.TRIGGER && !input.human; }
    public boolean waitsForPerson(Ticket ticket) {
        Assignment a=ticket.latestAssignment();
        if (a == null || a.report == null || !Set.of(ReportKind.BLOCKED,ReportKind.FAILED).contains(a.report.kind())) return false;
        return ticket.inputs.subList(Math.min(a.reportInputCount,ticket.inputs.size()),ticket.inputs.size()).stream().noneMatch(i -> i.kind == InputKind.TRIGGER && i.human && !i.contextOnly);
    }
    private void wake(String ticketId) {
        Ticket ticket=requireTicket(ticketId);
        if (ticket.status == TicketStatus.OPEN || ticket.stopped || ticket.undelivered.isEmpty() || processing().paused()) return;
        if (!validMandate(ticket.mandate)) { releaseUnread(ticket); ticket.undelivered.clear(); save(ticket); return; }
        Input first=ticket.inputs.stream().filter(i -> ticket.undelivered.contains(i.id) && i.human && !i.contextOnly).findFirst().orElse(null);
        if (first == null) return;
        ticket.status=TicketStatus.OPEN; ticket.ended=null; newAssignment(ticket);
        Input resumed=input(first.triggerId,"Resumed under unchanged mandate",false,InputKind.RESUMED);
        ticket.inputs.add(resumed); ticket.undelivered.addFirst(resumed.id); ticket.updatedAt=now(); save(ticket);
        emit("ticket.resumed",Map.of("ticketId",ticketId,"triggerId",first.triggerId));
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
    public void assignmentStarted(String ticketId,String assignmentId) {
        store.atomic(() -> {
            Ticket ticket=requireTicket(ticketId); Assignment assignment=ticket.assignments.stream().filter(a -> a.id.equals(assignmentId)).findFirst().orElse(null);
            if (assignment == null || assignment.status == AssignmentStatus.ENDED) return null;
            assignment.status=AssignmentStatus.RUNNING; if (assignment.startedAt == null) assignment.startedAt=now();
            ticket.updatedAt=now(); save(ticket); emit("assignment.started",Map.of("ticketId",ticketId,"assignmentId",assignmentId)); return null;
        });
    }
    private Assignment current(Ticket ticket,String assignmentId) {
        Assignment current=ticket.latestAssignment();
        if (ticket.status != TicketStatus.OPEN || current == null || !current.id.equals(assignmentId) || current.status == AssignmentStatus.ENDED) throw new IllegalStateException("Stale or ended assignment");
        return current;
    }
    private Assignment running(Ticket ticket,String assignmentId) {
        Assignment a=ticket.latestAssignment(); return ticket.status == TicketStatus.OPEN && a != null && a.id.equals(assignmentId) && a.status == AssignmentStatus.RUNNING ? a : null;
    }
    /** delivered 与 read 是不同的事实：送到会话的消息还可能没有进入模型的一轮。 */
    public void inputsDelivered(String ticketId,String assignmentId,List<String> inputIds) {
        store.atomic(() -> {
            Ticket ticket=requireTicket(ticketId); Assignment a=running(ticket,assignmentId); if (a == null) return null;
            Set<String> ids=Set.copyOf(inputIds);
            for (Input input : ticket.inputs) if (ids.contains(input.id)) { input.deliveredAt=now(); input.deliveredTo=assignmentId; input.held=false; }
            ticket.undelivered.removeAll(ids); ticket.delivery=null; ticket.updatedAt=now(); a.deliveredAt=now(); save(ticket); return null;
        });
    }
    public void inputsRead(String ticketId,String assignmentId,List<String> inputIds) {
        store.atomic(() -> { Ticket ticket=requireTicket(ticketId); if (running(ticket,assignmentId) != null) inputsRead(ticketId,inputIds); return null; });
    }
    public void inputsRead(String ticketId,List<String> inputIds) {
        store.atomic(() -> {
            Ticket ticket=requireTicket(ticketId); if (ticket.status != TicketStatus.OPEN) return null;
            Set<String> ids=Set.copyOf(inputIds);
            for (Input input : ticket.inputs) if (ids.contains(input.id)) { if (input.readAt == null) input.readAt=now(); input.acknowledged=true; }
            save(ticket); return null;
        });
    }
    public void turnEnded(String ticketId,String assignmentId) {
        store.atomic(() -> { Ticket t=requireTicket(ticketId); Assignment a=running(t,assignmentId); if (a != null) { a.idleAt=now(); save(t); } return null; });
    }
    public void deliveryFailed(String ticketId,String assignmentId,String error) {
        store.atomic(() -> { Ticket t=requireTicket(ticketId); if (running(t,assignmentId) != null) { t.delivery=new DeliveryFailure(error.substring(0,Math.min(300,error.length())),now()); save(t); emit("delivery.failed",Map.of("ticketId",ticketId)); } return null; });
    }
    /** 兼容同步执行器；新异步执行器应分别提交 delivered/read。此方法不宣称人类请求已被承接。 */
    public void acknowledgeInput(String ticketId,String inputId) {
        store.atomic(() -> {
            Ticket t=requireTicket(ticketId); Assignment a=t.latestAssignment();
            if (t.status != TicketStatus.OPEN || a == null) throw new IllegalStateException("Ticket ended");
            if (t.inputs.stream().noneMatch(i -> i.id.equals(inputId))) throw new IllegalArgumentException("Unknown ticket input");
            inputsDelivered(ticketId,a.id,List.of(inputId)); inputsRead(ticketId,List.of(inputId)); return null;
        });
    }
    /** 语义承接：与「模型读过」不同，会阻止任务结束时把该请求释放给 Eval。 */
    public void acknowledge(String ticketId,String triggerId) {
        store.atomic(() -> {
            Ticket t=requireTicket(ticketId); Trigger trigger=requireTrigger(triggerId);
            if (t.status != TicketStatus.OPEN || !trigger.human() || !reaches(t,trigger,false)) throw new IllegalArgumentException("Not an actionable human message for this ticket");
            Disposition d=disposition(triggerId); d.acked.add(ticketId); save(d);
            for (Input input : t.inputs) if (Objects.equals(input.triggerId,triggerId)) input.acknowledged=true;
            save(t); return null;
        });
    }
    public Ticket report(String ticketId,Report report) {
        Ticket ticket=requireTicket(ticketId); if (ticket.latestAssignment() == null) throw new IllegalStateException("No assignment");
        report(ticketId,ticket.latestAssignment().id,report); return requireTicket(ticketId);
    }
    /** READY 不等于工单结束：绑定了 PR 的工单必须等待对应 Thread 关闭。 */
    public void report(String ticketId,String assignmentId,Report report) {
        store.atomic(() -> {
            Ticket ticket=requireTicket(ticketId); Assignment a=current(ticket,assignmentId);
            if (report.question() != null && (report.kind() == ReportKind.READY || report.answers() != null)) throw new IllegalArgumentException("Question cannot accompany ready or answer");
            if (report.askedBy() != null && report.question() == null) throw new IllegalArgumentException("askedBy needs a question");
            if (report.askedBy() != null) validatePerson(ticket,report.askedBy(),true);
            if (report.answers() != null) validatePerson(ticket,report.answers(),false);
            Attention before=ticket.attention;
            if (report.kind() == ReportKind.READY || report.questionSpecified() && report.question() == null) ticket.attention=null;
            else if (report.question() != null && (before == null || !before.question().equals(report.question()))) ticket.attention=new Attention(id("attention"),report.question(),report.askedBy());
            if (before != null && !Objects.equals(before,ticket.attention)) withdrawReply("attention:"+before.id());
            a.report=report; a.reportAt=now(); a.reportInputCount=ticket.inputs.size(); a.status=AssignmentStatus.RUNNING; ticket.updatedAt=now();
            if (report.answers() != null) { ticket.answers.add(report.answers()); Disposition d=disposition(report.answers()); d.acked.add(ticket.id); save(d); }
            if (Set.of(ReportKind.BLOCKED,ReportKind.FAILED).contains(report.kind())) {
                for (Input input : ticket.inputs) if (automatic(input) && ticket.undelivered.remove(input.id)) input.held=true;
            }
            save(ticket);
            ClosedThread done=followsDone(ticket);
            if (report.kind() == ReportKind.DROPPED) end(ticket,"executor",done == null ? "dropped" : done.outcome(),now(),null,null);
            else if (report.kind() == ReportKind.READY && (ticket.follows.isEmpty() || done != null)) end(ticket,"executor",done == null ? "completed" : done.outcome(),now(),null,null);
            emit("worker.report",Map.of("ticketId",ticketId,"assignmentId",assignmentId,"state",report.kind().name()));
            if (ticket.attention != null && !Objects.equals(before,ticket.attention)) emit("worker.attention",Map.of("ticketId",ticketId,"attentionId",ticket.attention.id()));
            return null;
        });
    }
    private void validatePerson(Ticket ticket,String triggerId,boolean channelRequired) {
        Trigger trigger=requireTrigger(triggerId);
        if (!trigger.human() || channelRequired && trigger.channel() == null || !reaches(ticket,trigger,false)) throw new IllegalArgumentException("Question/answer references an unreached human message");
    }
    private long readUntil(Ticket ticket,boolean progress) {
        long until=0;
        for (Assignment a : ticket.assignments) {
            if (a.report != null && (progress || a.report.kind() != ReportKind.PROGRESS)) until=Math.max(until,a.reportAt);
            if (a.idleAt != null) until=Math.max(until,a.idleAt);
        }
        return until;
    }
    private boolean readBefore(Input input,long until) { return input.readAt != null && input.readAt <= until; }
    private void releaseUnread(Ticket ticket) { releaseUnread(ticket,null); }
    private void releaseUnread(Ticket ticket,String stopTriggerId) {
        long until=readUntil(ticket,true);
        for (Input input : ticket.inputs) if (input.kind == InputKind.TRIGGER && input.triggerId != null) {
            Disposition d=disposition(input.triggerId);
            boolean owner=d.owners.remove(ticket.id);
            boolean unread=ticket.undelivered.contains(input.id) || !readBefore(input,until);
            if (unread) { d.fanout.remove(ticket.id); d.context.remove(ticket.id); }
            save(d);
            Decision decision=d.decisionId == null ? null : store.get("decision",d.decisionId,Decision.class);
            boolean settled=decision != null && Set.of(DecisionKind.ACCEPT,DecisionKind.IGNORE,DecisionKind.WOULD_ACCEPT).contains(decision.kind()) && !"routed to existing ticket".equals(decision.reason());
            boolean stopMessage=Objects.equals(input.triggerId,stopTriggerId) && d.handbacks.isEmpty();
            if (owner && unread && d.acked.isEmpty() && !settled && !stopMessage && owners(input.triggerId).isEmpty()) queue(requireTrigger(input.triggerId));
        }
    }
    public boolean handback(String ticketId,String triggerId,String reason) {
        return store.atomic(() -> {
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Handback needs a reason");
            Ticket ticket=requireTicket(ticketId); requireTrigger(triggerId); Disposition d=disposition(triggerId);
            if (ticket.inputs.stream().noneMatch(i -> Objects.equals(i.triggerId,triggerId))) throw new IllegalArgumentException("Trigger never reached ticket");
            if (d.handbacks.containsKey(ticketId)) throw new IllegalStateException("Ticket already handed this trigger back");
            boolean owner=owners(triggerId).contains(ticketId); d.owners.remove(ticketId); d.handbacks.put(ticketId,reason); save(d);
            emit("trigger.handback",Map.of("ticketId",ticketId,"triggerId",triggerId,"reason",reason));
            if (owner && owners(triggerId).isEmpty()) { queue(requireTrigger(triggerId)); return true; } return false;
        });
    }
    private void requeueUntaken(Ticket ticket,Assignment replaced) {
        List<String> restore=ticket.inputs.stream().filter(i -> i.kind == InputKind.TRIGGER && i.readAt == null && replaced.id.equals(i.deliveredTo) && !ticket.undelivered.contains(i.id)).map(i -> i.id).toList();
        ticket.undelivered.addAll(0,restore);
    }
    private void endAssignment(Ticket ticket,Assignment assignment,String reason) {
        assignment.status=AssignmentStatus.ENDED; assignment.endedAt=now(); assignment.endedReason=reason;
        store.enqueue(EffectKind.STOP,assignment.id,Map.of("ticketId",ticket.id,"assignmentId",assignment.id,"reason",reason),now());
        emit("assignment.ended",Map.of("ticketId",ticket.id,"assignmentId",assignment.id,"reason",reason));
    }
    public Ticket retryTicket(String ticketId) {
        return store.atomic(() -> {
            Ticket ticket=requireTicket(ticketId); Assignment previous=ticket.latestAssignment();
            if (ticket.status != TicketStatus.OPEN || previous == null || !(waitsForPerson(ticket) || ticket.delivery != null)) throw new IllegalStateException("Ticket is not retryable");
            endAssignment(ticket,previous,"retried"); requeueUntaken(ticket,previous); newAssignment(ticket); ticket.updatedAt=now(); save(ticket); return ticket;
        });
    }
    public void assignmentInterrupted(String ticketId,String assignmentId) {
        store.atomic(() -> {
            Ticket ticket=requireTicket(ticketId); Assignment a=running(ticket,assignmentId); if (a == null) return null;
            boolean busy=a.deliveredAt != null && a.report != null && a.deliveredAt >= a.reportAt;
            boolean settled=a.report != null && Set.of(ReportKind.WAITING,ReportKind.READY,ReportKind.BLOCKED,ReportKind.FAILED).contains(a.report.kind()) && !busy;
            if (!settled) {
                endAssignment(ticket,a,"hostRestarted"); requeueUntaken(ticket,a); newAssignment(ticket);
                addInput(ticket,null,"Host restarted. Inspect worktree, git history and external state before continuing. Do not repeat completed external operations.",false,InputKind.TEXT);
                save(ticket);
            } else for (Input input : ticket.inputs) if (ticket.undelivered.contains(input.id))
                store.enqueue(EffectKind.INPUT,input.id,Map.of("ticketId",ticket.id,"assignmentId",a.id,"inputId",input.id),now());
            return null;
        });
    }
    public Ticket stopTicket(String ticketId) { return stopTicket(ticketId,null); }
    public Ticket stopTicket(String ticketId,String reason) {
        return store.atomic(() -> { Ticket ticket=requireTicket(ticketId); end(ticket,"human","stopped",now(),reason,null); return ticket; });
    }
    private void stop(Ticket ticket) { end(ticket,"human","stopped",now(),null,null); }
    private void end(Ticket ticket,String by,String outcome,long at,String reason,String stopTriggerId) {
        if (ticket.status == TicketStatus.ENDED) return;
        releaseUnread(ticket,stopTriggerId); ticket.status=TicketStatus.ENDED; ticket.stopped=by.equals("human");
        ticket.ended=new TicketEnd(by,outcome,at,reason); ticket.updatedAt=now(); ticket.undelivered.clear();
        if (ticket.attention != null) withdrawReply("attention:"+ticket.attention.id()); ticket.attention=null; ticket.delivery=null;
        for (Assignment a : ticket.assignments) if (a.status != AssignmentStatus.ENDED) { endAssignment(ticket,a,outcome); a.endedAt=at; }
        save(ticket); emit("ticket.ended",Map.of("ticketId",ticket.id,"by",by,"outcome",outcome));
    }
    public void stopFromTrigger(String ticketId,String triggerId) {
        store.atomic(() -> {
            Trigger trigger=requireTrigger(triggerId);
            if (!trigger.human() || gate(trigger,hasOwnTicket(trigger)) != Strength.EXPLICIT || !sharesOwnThread(ticketId,trigger)) throw new IllegalStateException("Stop requires explicit authority on ticket thread");
            end(requireTicket(ticketId),"human","stopped",now(),null,triggerId); return null;
        });
    }
    /** 路由器明确确认消息指向此工单后调用；不是 Executor 可自由使用的扩权工具。 */
    public Ticket attach(String ticketId, String triggerId, String note) {
        return store.atomic(() -> {
            Ticket ticket = requireTicket(ticketId); Trigger trigger = requireTrigger(triggerId);
            Disposition disposition = disposition(triggerId);
            if (ticket.stopped || ticket.status == TicketStatus.ENDED && !validMandate(ticket.mandate)) throw new IllegalStateException("Cannot attach under withdrawn or revoked mandate");
            if (!owners(triggerId).isEmpty()) throw new IllegalStateException("Follow-up still belongs to executor");
            if (disposition.handbacks.containsKey(ticketId)) throw new IllegalStateException("Ticket already handed this trigger back");
            for (ThreadRef ref : trigger.threads()) subscribe(ticket.id,ref,false);
            if (trigger.channel() != null && !ticket.channels.contains(trigger.channel())) ticket.channels.add(trigger.channel());
            Input existing=ticket.inputs.stream().filter(i -> trigger.id().equals(i.triggerId)).findFirst().orElse(null);
            boolean fresh=disposition.requeued || !disposition.fanout.contains(ticket.id) || disposition.context.contains(ticket.id) || ticket.status == TicketStatus.ENDED;
            if (existing != null && note != null && ticket.undelivered.contains(existing.id)) existing.note=note;
            else if (existing == null || fresh || note != null) {
                addInput(ticket,trigger.id(),trigger.text(),false); ticket.inputs.getLast().note=note;
            }
            disposition.requeued=false; disposition.context.remove(ticket.id); disposition.fanout.add(ticket.id);
            if (trigger.human()) disposition.owners.add(ticket.id);
            save(disposition); save(ticket); wake(ticket.id);
            // 被附加工单的消息不再由 Eval 决定；同批其他消息重新排队。
            ThreadState thread = thread(trigger.threads().getFirst().key());
            thread.pending.remove(triggerId);
            if (thread.evaluating != null && thread.evaluating.triggerIds().contains(triggerId)) {
                if (thread.evaluating.triggerIds().size() == 1) {
                    record(thread.evaluating,DecisionKind.IGNORE,ticketId,"routed to existing ticket"); thread.evaluating=null;
                } else {
                    reassess(thread,"routed to existing ticket",Set.of(triggerId)); thread=thread(thread.key);
                }
            }
            save(thread); return requireTicket(ticketId);
        });
    }
    /** 面板人工操作：取消订阅不撤销已经收到的 follow-up 所有权。 */
    public boolean unsubscribe(ThreadRef ref) {
        return store.atomic(() -> { ThreadState thread = thread(ref.key()); boolean had = !thread.subscriptions.isEmpty();
            for (Subscription subscription : thread.subscriptions) { Ticket ticket=requireTicket(subscription.ticketId()); ticket.follows.removeIf(f -> f.thread.equals(ref.key())); save(ticket); }
            thread.subscriptions.clear(); save(thread); emit("thread.unsubscribed",Map.of("threadKey",ref.key())); return had; });
    }
    /** 人工重评不可偷走 Executor 正在负责的消息；须先 handback。 */
    public boolean reevaluate(String triggerId) {
        return store.atomic(() -> {
            Trigger trigger = requireTrigger(triggerId);
            if (!owners(triggerId).isEmpty()) throw new IllegalStateException("Follow-up still belongs to an executor");
            ThreadState thread = thread(trigger.threads().getFirst().key());
            if (thread.evaluating != null && thread.evaluating.triggerIds().contains(triggerId)) return false;
            Disposition d=disposition(triggerId); d.requeued=true; d.owners.clear(); save(d);
            queue(trigger); emit("trigger.requeued",Map.of("triggerId",triggerId)); return true;
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
    /** 所有权只属于活跃工单，或暂停期间等待恢复的工单；关联 Thread 永远只补充上下文。 */
    private Set<String> owners(String triggerId) {
        Trigger trigger=requireTrigger(triggerId); Disposition d=disposition(triggerId);
        if (!trigger.human() || d.requeued) return Set.of();
        Set<String> result=new LinkedHashSet<>();
        for (String ticketId : d.owners) {
            if (d.handbacks.containsKey(ticketId) || d.context.contains(ticketId)) continue;
            Ticket ticket=ticket(ticketId);
            if (ticket != null && !ticket.stopped && (ticket.status == TicketStatus.OPEN || ticket.inputs.stream().anyMatch(i -> triggerId.equals(i.triggerId) && ticket.undelivered.contains(i.id)))) result.add(ticketId);
        }
        return result;
    }
    private boolean reaches(Ticket ticket,Trigger trigger,boolean allowContext) {
        if (ticket.originTriggerId.equals(trigger.id())) return true;
        if (ticket.inputs.stream().anyMatch(i -> trigger.id().equals(i.triggerId) && (allowContext || !i.contextOnly))) return true;
        return trigger.threads().stream().map(r -> thread(r.key())).flatMap(t -> t.subscriptions.stream()).anyMatch(s -> s.ticketId().equals(ticket.id) && (allowContext || !s.contextOnly()));
    }
    public Candidate candidate(String triggerId) {
        Trigger trigger=requireTrigger(triggerId); ThreadState thread=thread(trigger.threads().getFirst().key());
        if (thread.evaluating == null || !thread.evaluating.triggerIds().contains(triggerId)) return null;
        Map<String,Map<String,String>> handbacks=new LinkedHashMap<>();
        List<Trigger> triggers=thread.evaluating.triggerIds().stream().map(this::requireTrigger).toList();
        for (Trigger input : triggers) if (!disposition(input.id()).handbacks.isEmpty()) handbacks.put(input.id(),Map.copyOf(disposition(input.id()).handbacks));
        return new Candidate(thread.evaluating,thread,triggers,thread.subscriptions.stream().map(s -> requireTicket(s.ticketId())).toList(),handbacks);
    }
    public Evaluation evaluationById(String evalId) {
        return threads().stream().map(t -> t.evaluating).filter(Objects::nonNull).filter(e -> e.id().equals(evalId)).findFirst().orElse(null);
    }
    public void candidateDelivered(String evalId) {
        store.atomic(() -> {
            for (ThreadState thread : threads()) if (thread.evaluating != null && thread.evaluating.id().equals(evalId)) {
                Evaluation e=thread.evaluating; if (e.deliveredAt() != null) return null;
                thread.evaluating=new Evaluation(e.id(),e.threadKey(),e.triggerIds(),e.primaryTriggerId(),e.strength(),e.policyVersion(),e.createdAt(),now()+processing().evalTimeoutMs(),now(),processing().paused() ? now() : null,e.authority());
                save(thread); break;
            } return null;
        });
    }
    private Decision reassess(ThreadState thread,String reason,Set<String> excluded) {
        Evaluation evaluation=thread.evaluating; Decision decision=record(evaluation,DecisionKind.UNAVAILABLE,null,reason);
        List<String> pending=new ArrayList<>(evaluation.triggerIds().stream().filter(id -> !excluded.contains(id)).toList());
        for (String id : thread.pending) if (!excluded.contains(id) && !pending.contains(id)) pending.add(id);
        thread.pending=pending; thread.evaluating=null; thread.expiries=0; save(thread); return decision;
    }
    public Decision dismiss(String decisionId) {
        return store.atomic(() -> {
            Decision before=store.get("decision",decisionId,Decision.class);
            if (before == null || before.kind() != DecisionKind.UNAVAILABLE) throw new IllegalArgumentException("Only unavailable decisions can be dismissed");
            Decision after=new Decision(before.id(),before.evalId(),before.kind(),before.ticketId(),before.reason(),before.policyVersion(),before.at(),before.threadKey(),before.triggerIds(),before.strength(),true,before.audit());
            store.save("decision",after.id(),after); store.save("evalDecision",after.evalId(),after); return after;
        });
    }
    public Decision dismissDecision(String decisionId) { return dismiss(decisionId); }
    /** 面板有独立的人工释放操作。模型路由器的 reevaluate 不得调用本方法替代授权。 */
    public boolean sendFollowupToEval(String triggerId) {
        return store.atomic(() -> {
            Trigger trigger=requireTrigger(triggerId); ThreadState thread=thread(trigger.threads().getFirst().key());
            if (thread.evaluating != null && thread.evaluating.triggerIds().contains(triggerId)) return false;
            Disposition d=disposition(triggerId); d.requeued=true; d.owners.clear(); save(d); queue(trigger);
            emit("trigger.requeued",Map.of("triggerId",triggerId,"by","person")); return true;
        });
    }
    public Ticket bindThread(String ticketId,ThreadRef ref,boolean own) {
        return store.atomic(() -> {
            Ticket ticket=requireTicket(ticketId); if (ticket.status != TicketStatus.OPEN) throw new IllegalStateException("Ticket ended");
            subscribe(ticketId,ref,false);
            Follow follow=ticket.follows.stream().filter(f -> f.thread.equals(ref.key())).findFirst().orElse(null);
            if (follow == null) ticket.follows.add(new Follow(ref.key(),own)); else { follow.own |= own; follow.closed=null; }
            ticket.updatedAt=now(); save(ticket); emit("thread.bound",Map.of("ticketId",ticketId,"threadKey",ref.key())); return ticket;
        });
    }
    public ClosedThread followsDone(Ticket ticket) {
        List<Follow> own=ticket.follows.stream().filter(f -> f.own).toList();
        List<Follow> counted=own.isEmpty() ? ticket.follows : own;
        if (counted.isEmpty() || counted.stream().anyMatch(f -> f.closed == null)) return null;
        return counted.stream().map(f -> f.closed).max(Comparator.comparingLong(ClosedThread::at)).orElseThrow();
    }
    private boolean unfinished(Ticket ticket,long closeAt) {
        long until=readUntil(ticket,false);
        boolean taken=ticket.inputs.stream().filter(i -> i.kind == InputKind.TRIGGER && !readBefore(i,until))
            .anyMatch(i -> owners(i.triggerId).contains(ticket.id) && disposition(i.triggerId).acked.contains(ticket.id));
        Assignment a=ticket.latestAssignment();
        if (taken || a == null || a.createdAt <= closeAt) return taken;
        boolean later=ticket.inputs.stream().anyMatch(i -> i.kind != InputKind.POLICY_CHANGED && i.at > a.reportAt);
        return a.report == null || a.report.kind() != ReportKind.READY || !ticket.undelivered.isEmpty() || later;
    }
    public void threadClosed(ThreadRef ref,String outcome,String at) { threadClosed(ref,outcome,Instant.parse(at).toEpochMilli()); }
    public void threadClosed(ThreadRef ref,String outcome,long at) {
        if (!Set.of("closed","merged").contains(outcome)) throw new IllegalArgumentException("Thread outcome must be closed or merged");
        store.atomic(() -> {
            for (Ticket ticket : tickets()) if (ticket.status == TicketStatus.OPEN) {
                Follow follow=ticket.follows.stream().filter(f -> f.thread.equals(ref.key()) && f.closed == null).findFirst().orElse(null);
                if (follow == null) continue;
                follow.closed=new ClosedThread(outcome,at); save(ticket); ClosedThread done=followsDone(ticket);
                if (done != null && !unfinished(ticket,at)) end(ticket,"executor",done.outcome(),ticket.latestAssignment().createdAt > at ? now() : done.at(),null,null);
            }
            emit("thread.closed",Map.of("threadKey",ref.key(),"outcome",outcome)); return null;
        });
    }
    public boolean followForContext(String ticketId,ThreadRef ref) {
        return store.atomic(() -> {
            Ticket ticket=requireTicket(ticketId); if (ticket.status != TicketStatus.OPEN) throw new IllegalStateException("Ticket ended");
            if (thread(ref.key()).subscriptions.stream().anyMatch(s -> s.ticketId().equals(ticketId))) return false;
            subscribe(ticketId,ref,true); emit("thread.followed",Map.of("ticketId",ticketId,"threadKey",ref.key())); return true;
        });
    }
    public void markUnrelated(String ticketId,String threadKey,String reason) {
        store.atomic(() -> {
            Ticket ticket=requireTicket(ticketId);
            if (requireTrigger(ticket.originTriggerId).threads().stream().anyMatch(t -> t.key().equals(threadKey))) throw new IllegalArgumentException("Origin thread cannot be dropped by executor");
            if (ticket.follows.stream().anyMatch(f -> f.thread.equals(threadKey))) throw new IllegalArgumentException("Source-bound thread cannot be dropped by executor");
            ThreadState thread=thread(threadKey);
            if (!thread.subscriptions.removeIf(s -> s.ticketId().equals(ticketId))) throw new IllegalArgumentException("Ticket is not subscribed");
            save(thread);
            Set<String> triggers=new LinkedHashSet<>(); for (Input input : ticket.inputs) if (input.kind == InputKind.TRIGGER) triggers.add(input.triggerId);
            for (String triggerId : triggers) if (requireTrigger(triggerId).threads().stream().anyMatch(t -> t.key().equals(threadKey)) && owners(triggerId).contains(ticketId)) handback(ticketId,triggerId,reason);
            emit("thread.unsubscribed",Map.of("ticketId",ticketId,"threadKey",threadKey,"by","executor")); return null;
        });
    }
    public Ticket note(String ticketId,String text) {
        return store.atomic(() -> { Ticket ticket=requireTicket(ticketId); if (ticket.status != TicketStatus.OPEN) throw new IllegalStateException("Ticket ended"); addInput(ticket,null,text,true,InputKind.NOTE); save(ticket); return ticket; });
    }
    public boolean requestStatus(String ticketId) {
        return store.atomic(() -> {
            Ticket ticket=requireTicket(ticketId); Assignment a=ticket.latestAssignment();
            if (ticket.status != TicketStatus.OPEN || a == null || a.status != AssignmentStatus.RUNNING || a.report == null || a.report.kind() != ReportKind.WAITING) return false;
            addInput(ticket,null,"Report current status only. Do not resume implementation or perform external actions.",true,InputKind.TEXT); save(ticket); return true;
        });
    }
    public boolean executionHeld(String ticketId) { return waitsForPerson(requireTicket(ticketId)); }
    public TicketStage ticketStage(String ticketId) {
        Ticket ticket=requireTicket(ticketId); Assignment a=ticket.latestAssignment();
        if (ticket.status == TicketStatus.ENDED) return new TicketStage("ended",null);
        if (ticket.delivery != null) return new TicketStage("person","delivery");
        if (ticket.attention != null) return new TicketStage("person","answer");
        if (a != null && a.report != null && a.report.kind() == ReportKind.READY) return new TicketStage("person","review");
        if (waitsForPerson(ticket)) return new TicketStage("person",a.report.kind().name().toLowerCase(Locale.ROOT));
        if (a == null || a.status == AssignmentStatus.PENDING) return new TicketStage("working","queued");
        if (a.status == AssignmentStatus.ENDED || a.report != null && a.report.kind() == ReportKind.WAITING) return new TicketStage("idle",null);
        return new TicketStage("working",null);
    }
    public void describeThread(String key,Map<String,Object> metadata) {
        store.atomic(() -> { ThreadState thread=thread(key); thread.metadata.putAll(metadata); save(thread); return null; });
    }
    public void setExecutionIdentity(String ticketId,String sessionId,String directory,String branch) {
        store.atomic(() -> { Ticket ticket=requireTicket(ticketId); ticket.sessionId=sessionId; ticket.directory=directory; ticket.branch=branch; save(ticket); return null; });
    }
    public void putSourceState(String sourceId,String key,Object value) { store.putState("source:"+sourceId,key,value); }
    public <T> T getSourceState(String sourceId,String key,Class<T> type) { return store.getState("source:"+sourceId,key,type); }
    public List<OutboxMessage> listOutbox() { return store.list("outbox",OutboxMessage.class); }
    public boolean withdrawReply(String messageId) { return markReplyDropped(messageId,"withdrawn"); }
    public boolean markReplyDropped(String messageId,String reason) {
        return store.atomic(() -> {
            OutboxMessage message=outboxMessage(messageId);
            if (message == null || !message.status().equals("pending")) return false;
            store.save("outbox",messageId,new OutboxMessage(message.id(),message.ticketId(),message.channel(),message.text(),"dropped",message.receipt(),now(),reason)); return true;
        });
    }
    /** 回复目的决定收件地址；不能随意把上下文 Thread 变成写渠道。 */
    public Reply reply(ReplyRequest request) {
        return store.atomic(() -> {
            if (request.purpose() == null || request.text() == null || request.text().isBlank()) throw new IllegalArgumentException("Reply purpose and text required");
            Trigger trigger=request.triggerId() == null ? null : requireTrigger(request.triggerId());
            Ticket ticket=request.ticketId() == null ? trigger == null ? null : tickets().stream().filter(t -> reaches(t,trigger,true)).findFirst().orElse(null) : requireTicket(request.ticketId());
            Channel channel; String messageId;
            switch (request.purpose()) {
                case ACKNOWLEDGEMENT -> {
                    if (ticket == null || trigger == null || trigger.channel() == null) throw new IllegalArgumentException("Acknowledgement requires reached human message with channel");
                    acknowledge(ticket.id,trigger.id()); channel=trigger.channel(); messageId="ack:"+trigger.id();
                }
                case ANSWER -> {
                    if (trigger == null || !trigger.human() || trigger.channel() == null) throw new IllegalArgumentException("Answer requires human question with channel");
                    messageId=replyId(trigger.id(),request.text());
                    if (ticket != null && !ticket.answers.contains(trigger.id()) && outboxMessage(messageId) == null) throw new IllegalStateException("Worker has not reported an answer");
                    channel=trigger.channel();
                    if (ticket != null) { ticket.answers.remove(trigger.id()); save(ticket); }
                }
                case ATTENTION -> {
                    if (ticket == null || ticket.attention == null || !ticket.attention.question().equals(request.question())) throw new IllegalStateException("Question does not match current attention");
                    Trigger asker=ticket.attention.triggerId() == null ? null : requireTrigger(ticket.attention.triggerId());
                    channel=asker == null ? sourceChannel(ticket) : asker.channel(); messageId="attention:"+ticket.attention.id();
                }
                case RESULT -> {
                    if (ticket == null) throw new IllegalArgumentException("Result needs ticket");
                    boolean ready=ticket.status == TicketStatus.OPEN && ticket.latestAssignment() != null && ticket.latestAssignment().report != null && ticket.latestAssignment().report.kind() == ReportKind.READY;
                    boolean completed=ticket.ended != null && Set.of("completed","merged","dropped").contains(ticket.ended.outcome());
                    if (!ready && !completed) throw new IllegalStateException("Ticket has no ready result");
                    channel=sourceChannel(ticket); messageId=replyId(ticket.id,request.text());
                }
                default -> throw new IllegalStateException("Unknown reply purpose");
            }
            if (channel == null) throw new IllegalStateException("No reply channel");
            boolean reused=outboxMessage(messageId) != null;
            if (!reused) {
                store.save("outbox",messageId,new OutboxMessage(messageId,ticket == null ? null : ticket.id,channel,request.text(),"pending",null));
                store.enqueue(EffectKind.REPLY,messageId,Map.of("messageId",messageId),now());
            }
            return new Reply(messageId,outboxMessage(messageId).status(),reused);
        });
    }
    private String replyId(String resource,String text) { return "reply:"+UUID.nameUUIDFromBytes((resource+"\n"+text).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private Channel sourceChannel(Ticket ticket) { Trigger origin=requireTrigger(ticket.originTriggerId); return origin.channel() == null ? ticket.channels.isEmpty() ? null : ticket.channels.getFirst() : origin.channel(); }

    public void configureSourceScope(String sourceId,SourceScope scope) {
        store.atomic(() -> {
            SourceScope previous=store.getState("sourceScope",sourceId,SourceScope.class);
            if (Objects.equals(previous,scope)) return null;
            Set<String> before=new HashSet<>(); for (Ticket ticket : tickets()) if (validMandate(ticket.mandate)) before.add(ticket.id);
            store.putState("sourceScope",sourceId,scope);
            for (Ticket ticket : tickets()) if (ticket.status == TicketStatus.OPEN && before.contains(ticket.id) && !validMandate(ticket.mandate)) {
                addInput(ticket,null,"Source receiving scope changed; mandate no longer revalidates. Current work is not cancelled.",true,InputKind.POLICY_CHANGED); save(ticket);
            }
            for (ThreadState thread : threads()) if (thread.evaluating != null && thread.evaluating.triggerIds().stream().anyMatch(id -> requireTrigger(id).source().equals(sourceId))) reassess(thread,"source scope changed",Set.of());
            emit("source.scopeChanged",Map.of("sourceId",sourceId)); return null;
        });
    }
    private boolean listened(Trigger trigger) {
        SourceScope scope=store.getState("sourceScope",trigger.source(),SourceScope.class);
        return scope == null || scope.enabled() && (scope.scopes().isEmpty() || scope.scopes().contains(trigger.scope()));
    }
    private boolean selfMessage(Trigger trigger) {
        SourceScope scope=store.getState("sourceScope",trigger.source(),SourceScope.class);
        if (scope == null || scope.selfActor() == null) return false;
        SourcePolicy policy=policy().sources().get(trigger.source());
        return policy != null && policy.caseInsensitiveActors() ? scope.selfActor().equalsIgnoreCase(trigger.actor()) : scope.selfActor().equals(trigger.actor());
    }

    private boolean listed(SourcePolicy source,Set<String> actors,String actor) {
        return source.caseInsensitiveActors() ? actors.stream().anyMatch(a -> a.equalsIgnoreCase(actor)) : actors.contains(actor);
    }

    /** 时间相同的事件继续按 id 排序；游标不会跳过同一毫秒内的其他消息。 */
    public TimelinePage threadTimeline(String threadKey,String cursor,int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        List<Trigger> remaining=store.list("trigger",Trigger.class).stream()
            .filter(t -> t.threads().stream().anyMatch(ref -> ref.key().equals(threadKey)))
            .sorted(Comparator.comparing(Trigger::at).thenComparing(Trigger::id))
            .filter(t -> cursor == null || (t.at()+"|"+t.id()).compareTo(cursor) > 0).limit(limit+1L).toList();
        List<Trigger> page=remaining.stream().limit(limit).toList();
        String next=remaining.size() > limit ? page.getLast().at()+"|"+page.getLast().id() : null;
        return new TimelinePage(page,next);
    }

}
