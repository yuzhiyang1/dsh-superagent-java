package io.github.dsh.runtime;

import io.github.dsh.core.Engine;
import io.github.dsh.core.SqliteStore;
import io.github.dsh.core.Model.*;
import java.time.Clock;
import java.util.List;

/**
 * 事务外执行副作用。一个实例串行 drain；请求接入可以在另一个线程调用 Engine。
 * 不把网络请求放入数据库事务，也不把「请求已发出」误写成「回执已确认」。
 */
public final class OrchestratorRuntime {
    private final Engine engine;
    private final SqliteStore store;
    private final Executor executor;
    private final ChannelSender channel;
    private final Clock clock;

    public OrchestratorRuntime(Engine engine, SqliteStore store, Executor executor, ChannelSender channel, Clock clock) {
        this.engine = engine; this.store = store; this.executor = executor; this.channel = channel; this.clock = clock;
    }

    /** 有界处理当前已到期的队列；失败保留到 SQLite，下一次到期重试，不忙等。 */
    public synchronized int drain(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("maximum must be positive");
        int processed = 0;
        while (processed < maximum) {
            List<Effect> effects = store.claimEffects(clock.millis(), 1);
            if (effects.isEmpty()) break;
            Effect effect = effects.getFirst();
            try {
                handle(effect);
                store.completeEffect(effect.id());
            } catch (Exception failure) {
                // 不保存异常正文：HTTP 错误可能包含 token 或敏感输入。
                store.failEffect(effect.id(), failure.getClass().getSimpleName(), clock.millis());
            }
            processed++;
        }
        return processed;
    }

    private void handle(Effect effect) throws Exception {
        switch (effect.kind()) {
            case REPLY -> {
                String messageId = effect.payload().get("messageId");
                OutboxMessage message = engine.outboxMessage(messageId);
                if (message != null && "pending".equals(message.status()))
                    engine.markReplySent(messageId, channel.send(message));
            }
            case STOP -> executor.stop(effect.payload().get("ticketId"), effect.payload().get("assignmentId"));
            case START, INPUT -> execute(effect);
        }
    }

    private void execute(Effect effect) throws Exception {
        Ticket ticket = engine.ticket(effect.payload().get("ticketId"));
        if (ticket == null || ticket.status == TicketStatus.ENDED || ticket.stopped) return;
        if (effect.kind() == EffectKind.INPUT) {
            String inputId = effect.payload().get("inputId");
            Input input = ticket.inputs.stream().filter(i -> i.id.equals(inputId)).findFirst().orElse(null);
            if (input == null || input.acknowledged) return;
        }
        Assignment assignment = ticket.latestAssignment();
        if (assignment == null || assignment.status == AssignmentStatus.ENDED) return;
        String expected = effect.payload().get("assignmentId");
        if (expected != null && !expected.equals(assignment.id)) return; // 旧 Assignment 的迟到事件不能污染新一轮。
        if (assignment.status == AssignmentStatus.PENDING) engine.assignmentStarted(ticket.id, assignment.id);
        Report report = executor.execute(ticket, assignment, () -> {
            Ticket latest = engine.ticket(ticket.id);
            return latest != null && latest.status == TicketStatus.OPEN && !latest.stopped
                    && latest.latestAssignment() != null && latest.latestAssignment().id.equals(assignment.id)
                    && latest.latestAssignment().status != AssignmentStatus.ENDED;
        });
        Ticket current = engine.ticket(ticket.id);
        if (current.status == TicketStatus.OPEN && !current.stopped && current.latestAssignment().id.equals(assignment.id)
                && current.latestAssignment().status != AssignmentStatus.ENDED) {
            store.atomic(() -> {
            for (Input input : ticket.inputs)
                if (!input.acknowledged) engine.acknowledgeInput(ticket.id, input.id);
            engine.report(ticket.id, assignment.id, report);
            if (report.kind() == ReportKind.READY || report.kind() == ReportKind.BLOCKED || report.kind() == ReportKind.FAILED)
                for (int i = 0; i < current.channels.size(); i++)
                    engine.queueReply(assignment.id + ":result:" + i, ticket.id, current.channels.get(i), report.summary());
            if (report.kind() == ReportKind.WAITING && report.question() != null) {
                String questionId = java.util.UUID.nameUUIDFromBytes(report.question().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                for (int i = 0; i < current.channels.size(); i++)
                    engine.queueReply(assignment.id + ":question:" + questionId + ":" + i,
                            ticket.id, current.channels.get(i), report.question());
            }
            return null;
            });
        }
    }
}
