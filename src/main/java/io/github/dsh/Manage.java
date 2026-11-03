package io.github.dsh;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** 本机操作人的管理入口，不是公开 HTTP API。模型和 Source 消息不能调用这个可信入口。 */
public final class Manage {
  private static final ObjectMapper JSON = new ObjectMapper();

  public static void main(String[] args) throws Exception {
    if (args.length < 2)
      throw new IllegalArgumentException(
          "Usage: Manage <database> list|show|stop|retry|answer|recover [ticketId] [answer text]");
    try (SqliteStore store = new SqliteStore(Path.of(args[0]))) {
      Engine engine = new Engine(store);
      Object result =
          switch (args[1]) {
            case "list" -> engine.tickets();
            case "show" -> engine.ticket(argument(args, 2));
            case "stop" -> engine.stopTicket(argument(args, 2));
            case "retry" -> engine.retryTicket(argument(args, 2));
            case "answer" ->
                answer(
                    engine,
                    argument(args, 2),
                    String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
            case "recover" -> {
              if (args.length != 3 || !"--old-process-stopped".equals(args[2]))
                throw new IllegalArgumentException(
                    "Recovery requires --old-process-stopped; never run concurrent workers");
              store.recoverEffects();
              yield Map.of("pendingEffects", store.effectCount());
            }
            default -> throw new IllegalArgumentException("Unknown management command");
          };
      System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
  }

  /** 人工答复留审计 Trigger，走同一 fan-out/resume 逻辑，不直接改 Assignment 状态。 */
  public static Ticket answer(Engine engine, String ticketId, String text) {
    if (text == null || text.isBlank()) throw new IllegalArgumentException("Answer text required");
    Ticket ticket = engine.ticket(ticketId);
    if (ticket == null || ticket.stopped)
      throw new IllegalStateException("Ticket missing or explicitly stopped");
    Trigger origin = engine.trigger(ticket.originTriggerId);
    engine.receive(
        new Trigger(
            "operator-answer-" + UUID.randomUUID(),
            origin.source(),
            origin.threads(),
            origin.actor(),
            true,
            false,
            true,
            origin.scope(),
            Instant.now().toString(),
            "[本机操作人答复] " + text,
            origin.channel()));
    return engine.ticket(ticketId);
  }

  private static String argument(String[] args, int n) {
    if (args.length <= n) throw new IllegalArgumentException("Missing argument " + n);
    return args[n];
  }
}
