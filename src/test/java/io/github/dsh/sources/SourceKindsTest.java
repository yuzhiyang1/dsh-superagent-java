package io.github.dsh.sources;

import static io.github.dsh.config.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsh.core.Model.*;
import io.github.dsh.service.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 第三个 Java 来源真正读本地消息队列、提交 SQLite cursor 并交付回信，未修改内置工厂分支。 */
class SourceKindsTest {
  @TempDir Path directory;

  static final class QueueKind implements SourceKind {
    public String id() {
      return "queue";
    }

    public JsonNode title() {
      return object("zh", "队列", "en", "Queue");
    }

    public ObjectNode defaultConfig() {
      return object("file", "", "conversations", List.of(), "writesEnabled", false);
    }

    public ObjectNode validateConfig(JsonNode value) {
      if (!value.isObject()
          || !value.path("file").isTextual()
          || !value.path("conversations").isArray()
          || !value.path("writesEnabled").isBoolean())
        throw new IllegalArgumentException("Invalid queue config");
      value
          .fieldNames()
          .forEachRemaining(
              k -> {
                if (!Set.of("file", "conversations", "writesEnabled").contains(k))
                  throw new IllegalArgumentException("Unknown queue field");
              });
      return ((ObjectNode) value).deepCopy();
    }

    public JsonNode fields() {
      return array(
          List.of(
              object(
                  "key", "file", "type", "text", "label", object("zh", "队列文件", "en", "Queue file")),
              object(
                  "key",
                  "conversations",
                  "type",
                  "rows",
                  "label",
                  object("zh", "对话", "en", "Conversations"),
                  "columns",
                  List.of(
                      object(
                          "key", "id", "type", "text", "label", object("zh", "ID", "en", "ID"))))));
    }

    public JsonNode policyFields() {
      return array(
          List.of(
              object(
                  "key",
                  "allowed",
                  "type",
                  "rows",
                  "label",
                  object("zh", "允许账号", "en", "Allowed actors"),
                  "columns",
                  List.of(
                      object(
                          "key", "id", "type", "text", "label", object("zh", "ID", "en", "ID"))))));
    }

    public JsonNode policyRows(JsonNode config) {
      return array(
          List.of(
              object(
                  "id",
                  "work",
                  "label",
                  object("zh", "工作", "en", "Work"),
                  "cells",
                  object(
                      "listedUnaddressed",
                      true,
                      "unlistedAddressed",
                      true,
                      "unlistedUnaddressed",
                      true))));
    }

    public Set<String> health() {
      return Set.of("queue", "queue-pull");
    }

    public Set<String> panelOnlyKeys() {
      return Set.of("file", "writesEnabled");
    }

    public Set<String> restartKeys() {
      return Set.of("file");
    }

    public JsonNode environment(JsonNode config, JsonNode setup) {
      return object(
          "variables",
          List.of(
              object(
                  "key",
                  "QUEUE_SOURCE_FILE",
                  "value",
                  config.path("file").asText(),
                  "description",
                  object("zh", "队列文件", "en", "Queue file"))));
    }

    public Scope scope(Trigger trigger, JsonNode config) {
      String conversation = Objects.toString(trigger.metadata().get("conversation"), "");
      for (JsonNode row : config.path("conversations"))
        if (row.path("id").asText().equals(conversation))
          return new Scope(true, true, conversation, null);
      return new Scope(false, false, conversation, "conversation_not_listed");
    }

    public boolean channelAllowed(Map<String, String> address, JsonNode config) {
      for (JsonNode row : config.path("conversations"))
        if (row.path("id").asText().equals(address.get("conversation"))) return true;
      return false;
    }

    public JsonNode channelLink(Map<String, String> address, JsonNode config) {
      return object(
          "url", "https://queue.example/" + address.get("conversation"), "label", title());
    }

    public SourceInstance create(
        JsonNode instance, JsonNode policy, Map<String, String> environment) {
      return new QueueInstance(instance);
    }
  }

  static final class QueueInstance implements SourceInstance {
    final boolean enabled;
    ObjectNode config;
    volatile SourceContext liveContext;

    QueueInstance(JsonNode instance) {
      enabled = instance.path("enabled").asBoolean();
      config = new QueueKind().validateConfig(instance.path("config"));
    }

    public String id() {
      return "queue";
    }

    public String kind() {
      return id();
    }

    public boolean enabled() {
      return enabled;
    }

    public Object configuration() {
      return config.deepCopy();
    }

    public void configure(JsonNode value) {
      config = new QueueKind().validateConfig(value);
    }

    public void start(SourceContext ctx) {
      liveContext = ctx;
      ctx.ready("queue-pull");
    }

    public void installScope(SourceContext ctx) {
      Set<String> scopes = new HashSet<>();
      config.path("conversations").forEach(c -> scopes.add(c.path("id").asText()));
      if (scopes.isEmpty()) scopes.add("__none__");
      ctx.engine().configureSourceScope(id(), new SourceScope(enabled, scopes, "robot"));
    }

    public PollResult poll(SourceContext ctx) throws Exception {
      if (!enabled || config.path("file").asText().isBlank())
        return new PollResult(0, 60, List.of());
      List<String> lines = Files.readAllLines(Path.of(config.path("file").asText()));
      Integer saved = ctx.state(id(), "position", Integer.class);
      int previous = saved == null ? 0 : saved;
      List<Trigger> events = new ArrayList<>();
      for (int i = previous; i < lines.size(); i++) {
        JsonNode m = MAPPER.readTree(lines.get(i));
        String conversation = m.path("conversation").asText();
        events.add(
            new Trigger(
                "queue:" + m.path("id").asText(),
                id(),
                List.of(new ThreadRef(id(), conversation)),
                m.path("actor").asText(),
                true,
                true,
                true,
                conversation,
                m.path("at").asText(),
                m.path("text").asText(),
                new Channel(id(), id() + ":" + conversation, Map.of("conversation", conversation)),
                Map.of("conversation", conversation),
                null,
                null));
      }
      ctx.store()
          .atomic(
              () -> {
                events.forEach(ctx.engine()::receive);
                ctx.state(id(), "position", lines.size());
                return null;
              });
      return new PollResult(events.size(), 60, List.of());
    }

    public Object read(SourceContext ctx, String resource) throws Exception {
      return Files.readAllLines(Path.of(config.path("file").asText()));
    }

    public Delivery send(SourceContext ctx, OutboxMessage message) throws Exception {
      if (!enabled || !config.path("writesEnabled").asBoolean())
        return new Delivery("waiting", null);
      Path replies = Path.of(config.path("file").asText() + ".replies");
      Files.writeString(
          replies,
          message.id() + ":" + message.text() + "\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      return new Delivery("sent", message.id());
    }
  }

  @Test
  void registrationRejectsDuplicateAndReservedHealthAndReleasesRemovedClaims() {
    SourceKinds kinds = SourceKinds.builtins().register(new QueueKind());
    assertEquals(3, kinds.ids().size());
    assertThrows(IllegalArgumentException.class, () -> kinds.register(new QueueKind()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SourceKinds()
                .register(
                    new SourceKind() {
                      public String id() {
                        return "bad";
                      }

                      public JsonNode title() {
                        return object();
                      }

                      public ObjectNode defaultConfig() {
                        return object();
                      }

                      public ObjectNode validateConfig(JsonNode n) {
                        return object();
                      }

                      public Set<String> health() {
                        return Set.of("worker");
                      }

                      public SourceInstance create(JsonNode i, JsonNode p, Map<String, String> e) {
                        return null;
                      }
                    }));
    kinds.unregister("queue");
    kinds.register(new QueueKind());
    assertNotNull(kinds.get("queue"));
  }

  @Test
  void injectedThirdKindRunsWithoutBuiltinServiceChangesAndKeepsOrphanSettings() throws Exception {
    SourceKinds kinds = SourceKinds.builtins().register(new QueueKind());
    Path queue = directory.resolve("queue.jsonl");
    Files.writeString(
        queue,
        object(
                    "id",
                    "m1",
                    "conversation",
                    "work",
                    "actor",
                    "alice",
                    "at",
                    Instant.now().toString(),
                    "text",
                    "Please handle queue work")
                .toString()
            + "\n");
    ServiceOptions options =
        new ServiceOptions(
            directory.resolve("state"),
            0,
            URI.create("http://127.0.0.1:1/"),
            "KEY",
            "fixture",
            false,
            Map.of());
    try (var service = new OrchestratorService(options, Clock.systemUTC(), kinds)) {
      ObjectNode policy = (ObjectNode) service.settings.value("policy");
      ((ObjectNode) policy.path("sources"))
          .set("queue", object("allowed", List.of(object("id", "alice")), "admission", object()));
      service.settings.save("policy", policy, service.settings.revision("policy"), "panel");
      assertTrue(service.settings.sourceIds().contains("queue"));
      ObjectNode source = (ObjectNode) service.settings.value("source:queue");
      ((ObjectNode) source.path("config"))
          .put("file", queue.toString())
          .put("writesEnabled", true)
          .set("conversations", array(List.of(object("id", "work"))));
      service.settings.save("source:queue", source, 0, "panel");
      SourceContext ctx = new SourceContext(service.engine, service.store, Clock.systemUTC());
      service.sources.get("queue").poll(ctx);
      assertNotNull(service.engine.trigger("queue:m1"));
      assertEquals(0, service.sources.get("queue").poll(ctx).received());
      assertEquals(1, ctx.state("queue", "position", Integer.class));
      service.engine.evaluateDue();
      Evaluation evaluation =
          service.engine.threads().stream()
              .map(t -> t.evaluating)
              .filter(Objects::nonNull)
              .findFirst()
              .orElseThrow();
      String ticket =
          service
              .engine
              .decide(evaluation.id(), Outcome.accept("queue work", "work", Set.of()))
              .ticketId();
      assertNotNull(service.engine.ticket(ticket));
      assertEquals(
          "sent",
          service
              .sources
              .send(
                  ctx,
                  new OutboxMessage(
                      "reply1",
                      ticket,
                      new Channel("queue", "queue:work", Map.of("conversation", "work")),
                      "Done",
                      "pending",
                      null,
                      null,
                      null))
              .status());
      assertTrue(Files.readString(Path.of(queue + ".replies")).contains("Done"));
      assertTrue(
          kinds.get("queue").channelAllowed(Map.of("conversation", "work"), source.path("config")));
      assertFalse(
          kinds
              .get("queue")
              .channelAllowed(Map.of("conversation", "other"), source.path("config")));
      assertEquals(
          "https://queue.example/work",
          kinds
              .get("queue")
              .channelLink(Map.of("conversation", "work"), source.path("config"))
              .path("url")
              .asText());
      assertTrue(
          service.environment(null).stream()
              .anyMatch(v -> v.get("key").equals("QUEUE_SOURCE_FILE")));
      assertEquals(
          2,
          service
              .settings
              .sourceKind("queue")
              .descriptor(source.path("config"))
              .path("fields")
              .size());
      kinds.unregister("queue");
      service.reloadSources();
      assertNull(service.sources.get("queue"));
      assertFalse(service.store.getState("sourceScope", "queue", SourceScope.class).enabled());
      assertTrue(service.settings.sourceIds().contains("queue"));
      assertEquals(
          queue.toString(),
          service.settings.value("source:queue").path("config").path("file").asText());
      assertThrows(
          RuntimeException.class,
          () ->
              service.settings.save(
                  "source:queue", source, service.settings.revision("source:queue"), "panel"));
      kinds.register(new QueueKind());
      service.reloadSources();
      assertNotNull(service.sources.get("queue"));
      assertEquals(
          queue.toString(),
          service.settings.value("source:queue").path("config").path("file").asText());
    }
  }

  @Test
  void declaredHealthIsIsolatedAndOldInstanceCannotOverwriteReplacement() throws Exception {
    SourceKinds kinds = SourceKinds.builtins().register(new QueueKind());
    ServiceOptions options =
        new ServiceOptions(
            directory.resolve("health"),
            0,
            URI.create("http://127.0.0.1:1/"),
            "KEY",
            "fixture",
            false,
            Map.of());
    try (var service = new OrchestratorService(options, Clock.systemUTC(), kinds)) {
      ObjectNode gh = (ObjectNode) service.settings.value("source:github");
      gh.put("enabled", false);
      service.settings.save("source:github", gh, 0, "panel");
      QueueInstance old = (QueueInstance) service.sources.get("queue");
      long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      while (old.liveContext == null && System.nanoTime() < until) Thread.sleep(10);
      assertNotNull(old.liveContext);
      old.liveContext.error("queue-pull", "queue failed");
      assertEquals("error", service.health().path("queue-pull").path("state").asText());
      assertThrows(
          IllegalArgumentException.class, () -> old.liveContext.error("worker", "spoofed"));
      ObjectNode change = (ObjectNode) service.settings.value("source:queue");
      ((ObjectNode) change.path("config"))
          .set("conversations", array(List.of(object("id", "new"))));
      service.settings.save("source:queue", change, 0, "panel");
      QueueInstance replacement = (QueueInstance) service.sources.get("queue");
      assertNotSame(old, replacement);
      until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      while (replacement.liveContext == null && System.nanoTime() < until) Thread.sleep(10);
      assertNotNull(replacement.liveContext);
      replacement.liveContext.ready("queue-pull");
      old.liveContext.error("queue-pull", "stale");
      assertEquals("ready", service.health().path("queue-pull").path("state").asText());
    }
  }

  @Test
  void builtinsDeclareDynamicPolicySyncAndTicketEnvironment() {
    SourceKind github = SourceKinds.builtins().get("github"),
        feishu = SourceKinds.builtins().get("feishu");
    assertTrue(github.caseInsensitiveActors());
    assertFalse(feishu.syncReady(feishu.defaultConfig(), feishu.defaultPolicy()));
    assertTrue(feishu.syncReady(feishu.defaultConfig(), object("syncChat", "oc_team")));
    Trigger request =
        new Trigger(
            "gh1",
            "github",
            List.of(new ThreadRef("github", "7")),
            "alice",
            true,
            true,
            true,
            "comments",
            Instant.now().toString(),
            "work",
            null,
            Map.of("number", 7, "head", "refs/pull/7/head"),
            null,
            null);
    assertEquals(
        "7",
        github
            .environmentValues(github.defaultConfig(), object(), request)
            .get("DSH_SUPERAGENT_GITHUB_NUMBER"));
    assertThrows(
        IllegalArgumentException.class, () -> github.validatePolicy(object("unknown", true)));
    assertEquals(4, github.policyRows(github.defaultConfig()).size());
  }
}
