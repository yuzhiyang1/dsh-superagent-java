package io.github.dsh.router;

import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import java.util.*;

/** 候选只拿有界事实：前五条、触发前后五条、最多八个关联工单，原文可按引用展开。 */
public final class CandidateContext {
  private final Engine engine;
  private final SqliteStore store;

  public CandidateContext(Engine engine, SqliteStore store) {
    this.engine = engine;
    this.store = store;
  }

  public Evaluation evaluation(String triggerId) {
    return engine.threads().stream()
        .map(t -> t.evaluating)
        .filter(Objects::nonNull)
        .filter(e -> e.triggerIds().contains(triggerId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No active evaluation for trigger"));
  }

  public Map<String, Object> card(Evaluation evaluation) {
    Trigger trigger = engine.trigger(evaluation.primaryTriggerId());
    List<Trigger> timeline =
        store.list("trigger", Trigger.class).stream()
            .filter(t -> t.threads().stream().anyMatch(r -> r.key().equals(evaluation.threadKey())))
            .sorted(Comparator.comparing(Trigger::at).thenComparing(Trigger::id))
            .toList();
    int index = timeline.indexOf(trigger);
    List<Map<String, Object>> window = new ArrayList<>(), stubs = new ArrayList<>();
    for (int i = 0; i < timeline.size(); i++) {
      Trigger item = timeline.get(i);
      int distance = Math.abs(i - index);
      boolean visible = i < 5 || distance <= 5;
      int max = item.id().equals(trigger.id()) ? 4000 : distance <= 2 ? 1200 : visible ? 400 : 50;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", item.id());
      row.put("from", item.actor());
      row.put("at", item.at());
      row.put(visible ? "text" : "preview", truncate(item.text(), max));
      if (item.text().length() > max) row.put("chars", item.text().length());
      (visible ? window : stubs).add(row);
    }
    Set<String> ticketIds = new LinkedHashSet<>();
    for (ThreadState thread : engine.threads())
      if (thread.key.equals(evaluation.threadKey())
          || thread.correlated.contains(evaluation.threadKey()))
        for (Subscription subscription : thread.subscriptions)
          ticketIds.add(subscription.ticketId());
    List<Ticket> related =
        ticketIds.stream()
            .map(engine::ticket)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(t -> t.status == TicketStatus.OPEN ? 0 : 1))
            .toList();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("kind", "candidate");
    out.put("eval_id", evaluation.id());
    out.put("trigger_id", trigger.id());
    out.put("source", trigger.source());
    out.put("strength", evaluation.strength().name().toLowerCase());
    out.put("policy_version", evaluation.policyVersion());
    out.put("text", truncate(trigger.text(), 4000));
    out.put("batch", evaluation.triggerIds());
    out.put("window", window);
    out.put("stubs", stubs);
    out.put(
        "related_tickets",
        related.stream()
            .limit(8)
            .map(
                t ->
                    Map.of(
                        "id",
                        t.id,
                        "title",
                        t.title,
                        "status",
                        t.status,
                        "scope",
                        t.mandate.scope()))
            .toList());
    out.put("tickets_total", related.size());
    Map<String, Object> handbacks = new LinkedHashMap<>();
    for (String id : evaluation.triggerIds())
      handbacks.put(id, engine.triggerDisposition(id).handbacks);
    out.put("handed_back", handbacks);
    return out;
  }

  public Map<String, Object> readMessage(String triggerId, String messageId, int offset) {
    Evaluation evaluation = evaluation(triggerId);
    String wanted = messageId == null ? triggerId : messageId;
    Trigger message = engine.trigger(wanted);
    if (message == null
        || message.threads().stream().noneMatch(t -> t.key().equals(evaluation.threadKey())))
      throw new IllegalArgumentException("Message not in this evaluation thread");
    if (offset < 0 || offset > message.text().length())
      throw new IllegalArgumentException("Invalid offset");
    int end = Math.min(offset + 4000, message.text().length());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", wanted);
    result.put("text", message.text().substring(offset, end));
    if (end < message.text().length()) result.put("next_offset", end);
    return result;
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
