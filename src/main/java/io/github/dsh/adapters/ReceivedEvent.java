package io.github.dsh.adapters;

import java.time.Instant;
import java.util.Map;

/** Source 的边界 DTO；actor/bot/addressed 只能从可信 API 元数据生成，不能让模型填写。 */
public record ReceivedEvent(String sourceId, String eventId, String threadId, String actor,
                            boolean bot, boolean addressed, String text, Instant at,
                            Map<String, String> replyAddress) {
    public ReceivedEvent { replyAddress = Map.copyOf(replyAddress); }
}
