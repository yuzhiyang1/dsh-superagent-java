package io.github.dsh.service;

import static io.github.dsh.config.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import io.github.dsh.core.Model.*;
import io.github.dsh.sources.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 真实组合根、SQLite、HTTP模型协议、Java worker和回信发件箱一起运行，不替换领域流程。 */
class ServiceIntegrationTest {
  @TempDir Path root;

  @Test
  void daemonRoutesEditsReportsRepliesAndRestarts() throws Exception {
    AtomicInteger workerSteps = new AtomicInteger(), routerSteps = new AtomicInteger();
    List<JsonNode> requests = new CopyOnWriteArrayList<>();
    HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    model.createContext(
        "/chat/completions",
        exchange -> {
          JsonNode request = MAPPER.readTree(exchange.getRequestBody());
          requests.add(request);
          boolean router = request.path("tools").toString().contains("orchestrator_route");
          JsonNode assistant;
          if (router && routerSteps.getAndIncrement() == 0)
            assistant =
                call(
                    "route",
                    "orchestrator_route",
                    object(
                        "decisions",
                        List.of(
                            object(
                                "kind",
                                "accept",
                                "trigger_id",
                                "request-1",
                                "title",
                                "Fix greeting",
                                "worktree_name",
                                "fix-greeting",
                                "acknowledgement",
                                "I will fix the greeting."))));
          else if (!router && workerSteps.getAndIncrement() == 0)
            assistant =
                call(
                    "write",
                    "write_file",
                    object(
                        "path",
                        "Greeting.java",
                        "content",
                        "class Greeting { String text = \"Hello\"; }\n"));
          else if (!router)
            assistant =
                call(
                    "report",
                    "orchestrator_report",
                    object(
                        "state",
                        "ready",
                        "summary",
                        "Updated Greeting.java and checked the text."));
          else assistant = object("role", "assistant", "content", "Recorded.");
          byte[] body =
              MAPPER.writeValueAsBytes(object("choices", List.of(object("message", assistant))));
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    model.start();
    ServiceOptions options =
        new ServiceOptions(
            root,
            0,
            URI.create("http://127.0.0.1:" + model.getAddress().getPort() + "/"),
            "TEST_KEY",
            "fixture-model",
            false,
            Map.of("TEST_KEY", "fixture"));
    List<String> replies = new CopyOnWriteArrayList<>();
    String ticketId;
    try (OrchestratorService service = new OrchestratorService(options)) {
      ObjectNode source = (ObjectNode) service.settings.value("source:github");
      source.put("enabled", false);
      service.settings.save(
          "source:github", source, service.settings.revision("source:github"), "panel");
      ObjectNode executor = (ObjectNode) service.settings.value("executor");
      executor.put("allowWrites", true);
      service.settings.save("executor", executor, service.settings.revision("executor"), "panel");
      service.engine.setPolicy(
          new Policy(
              "integration",
              AcceptMode.AUTO,
              Map.of("fixture", new SourcePolicy(Set.of("alice"), Set.of(), Map.of())),
              Set.of()));
      service.sources.register(new FixtureSource(replies));
      service.start();
      assertTrue(service.setup().path("problem").isNull(), service.setup().toString());
      service.engine.receive(trigger());
      service.requestPass();
      await(
          () ->
              !service.engine.tickets().isEmpty()
                  && service.engine.tickets().getFirst().latestAssignment() != null
                  && service.engine.tickets().getFirst().latestAssignment().report != null,
          service);
      Ticket ticket = service.engine.tickets().getFirst();
      ticketId = ticket.id;
      assertEquals(ReportKind.READY, ticket.latestAssignment().report.kind());
      assertTrue(
          Files.readString(Path.of(ticket.directory).resolve("Greeting.java")).contains("Hello"));
      await(() -> !replies.isEmpty(), service);
      assertEquals("I will fix the greeting.", replies.getFirst());
      assertTrue(service.transcript(ticket.id).toString().contains("write_file"));
      assertTrue(requests.size() >= 4);
      assertTrue(service.prompts.revision() >= 0);
    }
    try (OrchestratorService reopened = new OrchestratorService(options)) {
      assertNotNull(reopened.engine.ticket(ticketId));
      assertTrue(reopened.transcript(ticketId).toString().contains("Greeting.java"));
    } finally {
      model.stop(0);
    }
  }

  @Test
  void invalidSavedProviderKeepsRepairPanelButNeverDispatchesFallback() throws Exception {
    ServiceOptions options =
        new ServiceOptions(
            root, 0, URI.create("http://127.0.0.1:9/"), "KEY", "fallback", false, Map.of());
    try (OrchestratorService service = new OrchestratorService(options)) {
      ObjectNode source = (ObjectNode) service.settings.value("source:github");
      source.put("enabled", false);
      service.settings.save(
          "source:github", source, service.settings.revision("source:github"), "panel");
      ObjectNode executor = (ObjectNode) service.settings.value("executor");
      executor.set("model", object("provider", "removed-provider", "model", "private-model"));
      service.store.putState(
          "settings:block",
          "executor",
          object("block", "executor", "revision", 99, "value", executor));
    }
    try (OrchestratorService service = new OrchestratorService(options)) {
      service.start();
      assertEquals("modelProviderRequired", service.setup().path("problem").path("code").asText());
      service.engine.setPolicy(
          new Policy(
              "invalid-provider-test",
              AcceptMode.AUTO,
              Map.of("fixture", new SourcePolicy(Set.of("alice"), Set.of(), Map.of())),
              Set.of()));
      service.engine.receive(trigger());
      service.requestPass();
      Thread.sleep(150);
      assertTrue(service.engine.tickets().isEmpty());
      assertTrue(service.engine.threads().stream().noneMatch(thread -> thread.evaluating != null));
    }
  }

  static JsonNode call(String id, String name, JsonNode args) {
    return object(
        "role",
        "assistant",
        "content",
        null,
        "tool_calls",
        List.of(
            object(
                "id",
                id,
                "type",
                "function",
                "function",
                object("name", name, "arguments", args.toString()))));
  }

  static Trigger trigger() {
    return new Trigger(
        "request-1",
        "fixture",
        List.of(new ThreadRef("fixture", "thread-1")),
        "alice",
        true,
        true,
        true,
        "default",
        Instant.now().toString(),
        "Fix Greeting.java to say Hello",
        new Channel("fixture", "thread-1", Map.of()));
  }

  static void await(java.util.function.BooleanSupplier condition, OrchestratorService service)
      throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      service.requestPass();
      Thread.sleep(30);
    }
    assertTrue(
        condition.getAsBoolean(),
        () -> "health=" + service.health() + " sessions=" + service.sessions());
  }

  static final class FixtureSource implements SourceInstance {
    final List<String> replies;

    FixtureSource(List<String> replies) {
      this.replies = replies;
    }

    public String id() {
      return "fixture";
    }

    public String kind() {
      return "fixture";
    }

    public boolean enabled() {
      return true;
    }

    public Object configuration() {
      return Map.of();
    }

    public void configure(JsonNode ignored) {}

    public PollResult poll(SourceContext c) {
      return new PollResult(0, 60, List.of());
    }

    public Object read(SourceContext c, String path) {
      return Map.of();
    }

    public Object readContext(SourceContext c, Trigger t, String p, String cursor) {
      return Map.of("messages", List.of());
    }

    public Delivery send(SourceContext c, OutboxMessage m) {
      replies.add(m.text());
      return new Delivery("sent", "receipt-" + replies.size());
    }
  }
}
