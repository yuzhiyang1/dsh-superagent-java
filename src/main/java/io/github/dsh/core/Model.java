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
    public enum EffectKind { START, INPUT, STOP, REPLY }
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
                          String at, String text, Channel channel) {
        public Trigger {
            Objects.requireNonNull(id); Objects.requireNonNull(source); Objects.requireNonNull(actor);
            Objects.requireNonNull(at); Objects.requireNonNull(text); threads = List.copyOf(threads);
            if (threads.isEmpty() || threads.stream().anyMatch(t -> !source.equals(t.source())))
                throw new IllegalArgumentException("Trigger needs threads in its own source");
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
    public record SourcePolicy(Set<String> allowedActors, Set<String> allowedBots, Map<String,AdmissionRow> admission) {
        public SourcePolicy { allowedActors = Set.copyOf(allowedActors); allowedBots = Set.copyOf(allowedBots); admission = Map.copyOf(admission); }
    }
    public record Policy(String version, AcceptMode acceptMode, Map<String,SourcePolicy> sources, Set<String> enabledRules) {
        public Policy { Objects.requireNonNull(version); Objects.requireNonNull(acceptMode); sources = Map.copyOf(sources); enabledRules = Set.copyOf(enabledRules); }
        public static Policy safe() { return new Policy("initial", AcceptMode.OBSERVE, Map.of(), Set.of()); }
    }
    public record Mandate(String policyVersion, Strength strength, String scope, String source,
                          String actor, String originTriggerId, boolean followupBasis, Set<String> matchedRules) {
        public Mandate { matchedRules = Set.copyOf(matchedRules); }
    }
    public record Evaluation(String id, String threadKey, List<String> triggerIds, String primaryTriggerId,
                             Strength strength, String policyVersion, long createdAt, long deadline) {
        public Evaluation { triggerIds = List.copyOf(triggerIds); }
    }
    public record Outcome(DecisionKind kind, String title, String scope, Set<String> matchedRules, String reason, String ticketId) {
        public Outcome { Objects.requireNonNull(kind); matchedRules = matchedRules == null ? Set.of() : Set.copyOf(matchedRules); }
        public static Outcome accept(String title, String scope, Set<String> rules) { return new Outcome(DecisionKind.ACCEPT, title, scope, rules, "accepted", null); }
        public static Outcome ignore(String reason) { return new Outcome(DecisionKind.IGNORE, null, null, Set.of(), reason, null); }
    }
    public record Decision(String id, String evalId, DecisionKind kind, String ticketId, String reason, String policyVersion, long at) {}
    public record Report(ReportKind kind, String summary, String question) {
        public Report { Objects.requireNonNull(kind); Objects.requireNonNull(summary); }
        public Report(ReportKind kind, String summary) { this(kind, summary, null); }
    }
    public record Subscription(String ticketId, boolean contextOnly) {}
    public static final class ThreadState {
        public String key;
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
        public boolean contextOnly, acknowledged;
        public Input() {}
        public Input(String id, String triggerId, String text, boolean contextOnly) {
            this.id = id; this.triggerId = triggerId; this.text = text; this.contextOnly = contextOnly;
        }
    }
    public static final class Assignment {
        public String id, ticketId;
        public AssignmentStatus status = AssignmentStatus.PENDING;
        public Report report;
        public long createdAt;
        public Assignment() {}
        public Assignment(String id, String ticketId, long createdAt) { this.id = id; this.ticketId = ticketId; this.createdAt = createdAt; }
    }
    public static final class Ticket {
        public String id, title, originTriggerId;
        public Mandate mandate;
        public TicketStatus status = TicketStatus.OPEN;
        public boolean stopped;
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
        public Disposition() {}
        public Disposition(String id) { this.triggerId = id; }
    }
    public record Effect(String id, EffectKind kind, String key, Map<String,String> payload, int attempts, long dueAt, String lastError) {}
    public record OutboxMessage(String id, String ticketId, Channel channel, String text, String status, String receipt) {}
}
