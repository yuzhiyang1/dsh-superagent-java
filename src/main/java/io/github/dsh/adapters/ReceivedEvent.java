package io.github.dsh.adapters;

import java.time.Instant;
import java.util.Map;

/** Source 边界 DTO；身份和关联来自可信协议元数据，不能由模型消息正文授予。 */
public record ReceivedEvent(
    String sourceId,
    String eventId,
    String threadId,
    String actor,
    boolean bot,
    boolean addressed,
    String text,
    Instant at,
    Map<String, String> replyAddress,
    Map<String, Object> metadata,
    String url,
    String replyTo) {
  public ReceivedEvent {
    replyAddress = Map.copyOf(replyAddress);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  public ReceivedEvent(
      String sourceId,
      String eventId,
      String threadId,
      String actor,
      boolean bot,
      boolean addressed,
      String text,
      Instant at,
      Map<String, String> replyAddress) {
    this(
        sourceId,
        eventId,
        threadId,
        actor,
        bot,
        addressed,
        text,
        at,
        replyAddress,
        Map.of(),
        null,
        null);
  }
}
