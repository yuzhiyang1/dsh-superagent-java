package io.github.dsh.adapters;

import io.github.dsh.core.Engine;
import io.github.dsh.core.Model.*;
import java.util.*;

/** DTO -> 领域。scope 和 receiveAll 是操作者配置，不取自消息正文或模型响应。 */
public final class SourceBridge {
    private SourceBridge() {}
    public static Trigger toTrigger(ReceivedEvent event, String scope, boolean receiveAll) {
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("Trusted receiving scope required");
        String prefix = event.sourceId() + ":";
        String threadId = event.threadId().startsWith(prefix) ? event.threadId().substring(prefix.length()) : event.threadId();
        ThreadRef thread = new ThreadRef(event.sourceId(), threadId);
        Channel channel = event.replyAddress().isEmpty() ? null : new Channel(event.sourceId(), thread.key(), event.replyAddress());
        return new Trigger(event.eventId(), event.sourceId(), List.of(thread), event.actor(), !event.bot(),
                event.addressed(), receiveAll, scope, event.at().toString(), event.text(), channel);
    }
    /** 批次全部写入和游标推进原子提交；校验失败不会写入前半批。 */
    public static void receiveBatch(Engine engine, List<ReceivedEvent> events, String sourceId, String cursor,
                                    String scope, boolean receiveAll) {
        if (events.stream().anyMatch(e -> !sourceId.equals(e.sourceId()))) throw new IllegalArgumentException("Mixed-source batch refused");
        engine.receiveBatch(events.stream().map(e -> toTrigger(e, scope, receiveAll)).toList(), sourceId, cursor);
    }
}
