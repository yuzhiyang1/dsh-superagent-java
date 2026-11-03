package io.github.dsh.core;

import java.util.*;

/** 领域词汇。事件、授权用 record 保持不可变；聚合仅在 Engine 的事务内修改。 */
public final class Model {
    private Model() {}
    public enum AcceptMode { OFF, OBSERVE, AUTO }
    public enum Strength { EXPLICIT, INFERRED }
    public enum Admission { IGNORE, INFERRED, EXPLICIT }
    public enum TicketStatus { OPEN, ENDED }
    public enum AssignmentStatus { PENDING, RUNNING, ENDED }
    public enum ReportKind { PROGRESS, WAITING, READY, BLOCKED, FAILED, DROPPED }
    public enum DecisionKind { ACCEPT, STOP, IGNORE, UNAVAILABLE, WOULD_ACCEPT }
    public enum EffectKind { START, INPUT, STOP, REPLY, ASSESSMENT, RELAY, NOTICE }
    public record TimelinePage(List<Trigger> items,String nextCursor) {}
    public record DomainEvent(long sequence,String type,Map<String,String> payload,long at) {}
    public enum InputKind { TRIGGER, NOTE, POLICY_CHANGED, TEXT, RESUMED }
    public enum ReplyPurpose { ACKNOWLEDGEMENT, ANSWER, RESULT, ATTENTION }
    public record SourceScope(boolean enabled,Set<String> scopes,String selfActor) {
        public SourceScope { scopes=scopes == null ? Set.of() : Set.copyOf(scopes); }
    }
    public record ProcessingSettings(boolean paused, long evalTimeoutMs) {
        public ProcessingSettings { if (evalTimeoutMs < 1) throw new IllegalArgumentException("Positive evaluation timeout required"); }
    }
    public record Attention(String id, String question, String triggerId) {}
    public record TicketEnd(String by, String outcome, long at, String reason) {}
    public record DeliveryFailure(String error, long at) {}
    public record ClosedThread(String outcome, long at) {}
    public static final class Follow {
        public String thread; public boolean own; public ClosedThread closed;
        public Follow() {}
        public Follow(String thread, boolean own) { this.thread = thread; this.own = own; }
    }
    public record ReplyRequest(ReplyPurpose purpose, String ticketId, String triggerId, String question, String text) {}
    public record Reply(String id, String status, boolean reused) {}
    public record TicketStage(String stage, String action) {}
    public record Candidate(Evaluation evaluation, ThreadState thread, List<Trigger> triggers, List<Ticket> tickets,
                            Map<String,Map<String,String>> handbacks) {}

    public record ThreadRef(String source, String id) {
        public ThreadRef { Objects.requireNonNull(source); Objects.requireNonNull(id); }
        public String key() { return source + ":" + id; }
    }
    public record Channel(String source, String thread, Map<String,String> address) {
        public Channel { address = Map.copyOf(address); }
    }
    /** human/addressed/receiveAll 必须由可信 Source 提供，不能从消息正文解析权限。 */
    public record Trigger(String id, String source, List<ThreadRef> threads, String actor,
                          boolean human, boolean addressed, boolean receiveAll, String scope,
                          String at, String text, Channel channel, Map<String,Object> metadata, String url, String replyTo) {
        public Trigger {
            Objects.requireNonNull(id); Objects.requireNonNull(source); Objects.requireNonNull(actor);
            Objects.requireNonNull(at); Objects.requireNonNull(text); threads = List.copyOf(threads);
            metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            if (threads.isEmpty() || threads.stream().anyMatch(t -> !source.equals(t.source())))
                throw new IllegalArgumentException("Trigger needs threads in its own source");
        }
        public Trigger(String id,String source,List<ThreadRef> threads,String actor,boolean human,boolean addressed,
                       boolean receiveAll,String scope,String at,String text,Channel channel) {
            this(id,source,threads,actor,human,addressed,receiveAll,scope,at,text,channel,Map.of(),null,null);
        }
    }
    public record AdmissionRow(Admission listedUnaddressed, Admission unlistedAddressed, Admission unlistedUnaddressed) {
        public static AdmissionRow safe() { return new AdmissionRow(Admission.INFERRED, Admission.IGNORE, Admission.IGNORE); }
        public AdmissionRow {
            Objects.requireNonNull(listedUnaddressed); Objects.requireNonNull(unlistedAddressed); Objects.requireNonNull(unlistedUnaddressed);
            if (listedUnaddressed == Admission.EXPLICIT || unlistedUnaddressed == Admission.EXPLICIT)
                throw new IllegalArgumentException("Unaddressed messages cannot grant explicit authority");
        }
    }
    public record SourcePolicy(Set<String> allowedActors, Set<String> allowedBots, Map<String,AdmissionRow> admission, boolean caseInsensitiveActors) {
        public SourcePolicy { allowedActors = Set.copyOf(allowedActors); allowedBots = Set.copyOf(allowedBots); admission = Map.copyOf(admission); }
        public SourcePolicy(Set<String> allowedActors,Set<String> allowedBots,Map<String,AdmissionRow> admission) { this(allowedActors,allowedBots,admission,false); }
    }
    public record Policy(String version, AcceptMode acceptMode, Map<String,SourcePolicy> sources, Set<String> enabledRules, Map<String,String> ruleInstructions) {
        public Policy { Objects.requireNonNull(version); Objects.requireNonNull(acceptMode); sources = Map.copyOf(sources); enabledRules = Set.copyOf(enabledRules); ruleInstructions=ruleInstructions == null ? Map.of() : Map.copyOf(ruleInstructions); }
        public Policy(String version,AcceptMode acceptMode,Map<String,SourcePolicy> sources,Set<String> enabledRules) { this(version,acceptMode,sources,enabledRules,Map.of()); }
        public static Policy safe() { return new Policy("initial", AcceptMode.OBSERVE, Map.of(), Set.of()); }
    }
    public record Mandate(String policyVersion, Strength strength, String scope, String source,
                          String actor, String originTriggerId, boolean followupBasis, Set<String> matchedRules, String basis, String admissionCell) {
        public Mandate { matchedRules = Set.copyOf(matchedRules); }
        public Mandate(String policyVersion,Strength strength,String scope,String source,String actor,String originTriggerId,
                       boolean followupBasis,Set<String> matchedRules) {
            this(policyVersion,strength,scope,source,actor,originTriggerId,followupBasis,matchedRules,null,null);
        }
    }
    public record Evaluation(String id, String threadKey, List<String> triggerIds, String primaryTriggerId,
                             Strength strength, String policyVersion, long createdAt, long deadline, Long deliveredAt, Long pausedAt, Mandate authority) {
        public Evaluation { triggerIds = List.copyOf(triggerIds); }
        public Evaluation(String id,String threadKey,List<String> triggerIds,String primaryTriggerId,Strength strength,String policyVersion,long createdAt,long deadline,Long deliveredAt,Long pausedAt) {
            this(id,threadKey,triggerIds,primaryTriggerId,strength,policyVersion,createdAt,deadline,deliveredAt,pausedAt,null);
        }
        public Evaluation(String id, String threadKey, List<String> triggerIds, String primaryTriggerId, Strength strength,
                          String policyVersion, long createdAt, long deadline) {
            this(id,threadKey,triggerIds,primaryTriggerId,strength,policyVersion,createdAt,deadline,null,null);
        }
    }
    public record Outcome(DecisionKind kind, String title, String scope, Set<String> matchedRules, String reason, String ticketId, String shortName, String acknowledgement, String note, Map<String,Object> audit) {
        public Outcome { Objects.requireNonNull(kind); matchedRules = matchedRules == null ? Set.of() : Set.copyOf(matchedRules); audit=audit == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(audit)); }
        public Outcome(DecisionKind kind,String title,String scope,Set<String> matchedRules,String reason,String ticketId) { this(kind,title,scope,matchedRules,reason,ticketId,null,null,null,Map.of()); }
        public static Outcome accept(String title, String scope, Set<String> rules) { return new Outcome(DecisionKind.ACCEPT, title, scope, rules, "accepted", null); }
        public static Outcome ignore(String reason) { return new Outcome(DecisionKind.IGNORE, null, null, Set.of(), reason, null); }
    }
    public record Decision(String id, String evalId, DecisionKind kind, String ticketId, String reason, String policyVersion,
                           long at, String threadKey, List<String> triggerIds, Strength strength, boolean dismissed, Map<String,Object> audit) {
        public Decision { triggerIds = triggerIds == null ? List.of() : List.copyOf(triggerIds); audit=audit == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(audit)); }
        public Decision(String id,String evalId,DecisionKind kind,String ticketId,String reason,String policyVersion,long at,String threadKey,List<String> triggerIds,Strength strength,boolean dismissed) {
            this(id,evalId,kind,ticketId,reason,policyVersion,at,threadKey,triggerIds,strength,dismissed,Map.of());
        }
        public Decision(String id,String evalId,DecisionKind kind,String ticketId,String reason,String policyVersion,long at) {
            this(id,evalId,kind,ticketId,reason,policyVersion,at,null,List.of(),null,false);
        }
    }
    /** questionSpecified 区分「保持原问题」和「明确清除问题」，对应上游 undefined / null。 */
    public record Report(ReportKind kind, String summary, String question, boolean questionSpecified, String askedBy, String answers) {
        public Report { Objects.requireNonNull(kind); Objects.requireNonNull(summary); }
        public Report(ReportKind kind, String summary) { this(kind,summary,null,false,null,null); }
        public Report(ReportKind kind, String summary, String question) { this(kind,summary,question,question != null,null,null); }
        public static Report clearQuestion(ReportKind kind,String summary) { return new Report(kind,summary,null,true,null,null); }
    }
    public record Subscription(String ticketId, boolean contextOnly) {}
    public static final class ThreadState {
        public String key;
        public long createdAt, lastAt;
        public Map<String,Object> metadata = new LinkedHashMap<>();
        public List<Subscription> subscriptions = new ArrayList<>();
        public Set<String> correlated = new LinkedHashSet<>();
        public List<String> pending = new ArrayList<>();
        public Evaluation evaluating;
        public int expiries;
        public ThreadState() {}
        public ThreadState(String key) { this.key = key; }
    }
    public static final class Input {
        public String id, triggerId, text;
        public boolean contextOnly, acknowledged, human, held;
        public InputKind kind = InputKind.TRIGGER;
        public long at;
        public Long deliveredAt, readAt;
        public String deliveredTo, note;
        public Input() {}
        public Input(String id, String triggerId, String text, boolean contextOnly) {
            this.id = id; this.triggerId = triggerId; this.text = text; this.contextOnly = contextOnly;
        }
    }
    public static final class Assignment {
        public String id, ticketId;
        public AssignmentStatus status = AssignmentStatus.PENDING;
        public Report report;
        public long createdAt, reportAt;
        public int reportInputCount;
        public Long startedAt, deliveredAt, idleAt, endedAt;
        public String endedReason;
        public Assignment() {}
        public Assignment(String id, String ticketId, long createdAt) { this.id = id; this.ticketId = ticketId; this.createdAt = createdAt; }
    }
    public static final class Ticket {
        public String id, title, originTriggerId, shortName, sessionId, directory, branch;
        public Mandate mandate;
        public TicketStatus status = TicketStatus.OPEN;
        public boolean stopped;
        public long createdAt, updatedAt;
        public TicketEnd ended;
        public Attention attention;
        public DeliveryFailure delivery;
        public List<Follow> follows = new ArrayList<>();
        public List<String> undelivered = new ArrayList<>();
        public Set<String> answers = new LinkedHashSet<>();
        public List<String> originTriggerIds = new ArrayList<>();
        public List<Channel> channels = new ArrayList<>();
        public List<Input> inputs = new ArrayList<>();
        public List<Assignment> assignments = new ArrayList<>();
        public Ticket() {}
        public Assignment latestAssignment() { return assignments.isEmpty() ? null : assignments.getLast(); }
    }
    public static final class Disposition {
        public String triggerId;
        public Set<String> owners = new LinkedHashSet<>();
        public Map<String,String> handbacks = new LinkedHashMap<>();
        public Set<String> fanout = new LinkedHashSet<>(), context = new LinkedHashSet<>(), acked = new LinkedHashSet<>();
        public boolean requeued;
        public String evalId, decisionId, filtered;
        public Disposition() {}
        public Disposition(String id) { this.triggerId = id; }
    }
    public record Effect(String id, EffectKind kind, String key, Map<String,String> payload, int attempts, long dueAt, String lastError, long generation) {
        public Effect(String id,EffectKind kind,String key,Map<String,String> payload,int attempts,long dueAt,String lastError) {
            this(id,kind,key,payload,attempts,dueAt,lastError,0);
        }
    }
    public record OutboxMessage(String id, String ticketId, Channel channel, String text, String status, String receipt, Long droppedAt, String droppedReason) {
        public OutboxMessage(String id,String ticketId,Channel channel,String text,String status,String receipt) { this(id,ticketId,channel,text,status,receipt,null,null); }
    }
}
