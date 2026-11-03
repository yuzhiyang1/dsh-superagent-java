package io.github.dsh.sources;

import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import java.time.*;
import java.util.*;

/** 网络请求在事务外，接入结果与检查点在一个事务中落盘。 */
public record SourceContext(Engine engine, SqliteStore store, Clock clock) {
    public SourceContext { Objects.requireNonNull(engine); Objects.requireNonNull(store); Objects.requireNonNull(clock); }
    public <T> T state(String source, String key, Class<T> type) { return store.getState("source:" + source, key, type); }
    public void state(String source, String key, Object value) { store.putState("source:" + source, key, value); }
    public Instant now() { return clock.instant(); }
    public String overlap() { return now().minusSeconds(5).toString(); }
    public List<ThreadState> subscribed(String source) {
        return engine.threads().stream().filter(t -> t.key.startsWith(source + ":") && !t.subscriptions.isEmpty()).toList();
    }
    /** 活跃工单继续轮询，结束工单在最近活动后保留两周，供人类后续消息恢复。 */
    public boolean polled(ThreadState thread) {
        if (thread.subscriptions.stream().map(s -> engine.ticket(s.ticketId())).filter(Objects::nonNull)
                .anyMatch(t -> t.status == TicketStatus.OPEN && !t.stopped)) return true;
        long latest = Math.max(thread.createdAt,thread.lastAt);
        for (Trigger trigger : store.list("trigger", Trigger.class))
            if (trigger.threads().stream().anyMatch(ref -> ref.key().equals(thread.key)))
                latest = Math.max(latest, Instant.parse(trigger.at()).toEpochMilli());
        for (Subscription subscription : thread.subscriptions) {
            Ticket ticket = engine.ticket(subscription.ticketId());
            if (ticket != null && ticket.ended != null) latest = Math.max(latest,ticket.ended.at());
        }
        return latest > 0 && now().toEpochMilli() - latest < Duration.ofDays(14).toMillis();
    }
}
