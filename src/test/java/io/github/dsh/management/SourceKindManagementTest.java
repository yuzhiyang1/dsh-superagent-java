package io.github.dsh.management;

import static io.github.dsh.config.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsh.config.PromptService;
import io.github.dsh.config.VersionedSettings;
import io.github.dsh.core.Engine;
import io.github.dsh.core.SqliteStore;
import io.github.dsh.sources.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** docs/008：新增第三种来源不修改 Settings、HTTP 路由或浏览器代码。 */
class SourceKindManagementTest {
  @TempDir Path directory;

  static SourceKind queueKind() {
    return new SourceKind() {
      public String id() {
        return "webhook";
      }

      public JsonNode title() {
        return object("zh", "Webhook 队列", "en", "Webhook queue");
      }

      public JsonNode description() {
        return object("zh", "只接收配置对话。", "en", "Receives configured conversations only.");
      }

      public ObjectNode defaultConfig() {
        return object("conversations", List.of(), "pollSeconds", 30, "endpoint", "fixture");
      }

      public boolean caseInsensitiveActors() {
        return true;
      }

      public Set<String> panelOnlyKeys() {
        return Set.of("endpoint");
      }

      public Set<String> restartKeys() {
        return Set.of("endpoint");
      }

      public ObjectNode validateConfig(JsonNode input) {
        keys(input, "webhook", "conversations", "pollSeconds", "endpoint");
        ObjectNode config = defaults(input, defaultConfig());
        integer(config.get("pollSeconds"), "pollSeconds", 10, 3600);
        string(config.get("endpoint"), "endpoint", 1, 100);
        if (!config.path("conversations").isArray()) invalid("conversations", "Expected array");
        Set<String> ids = new HashSet<>();
        for (JsonNode row : config.path("conversations")) {
          keys(row, "conversation", "id", "listen");
          String id = string(row.get("id"), "id", 1, 80);
          if (!id.matches("[a-z0-9-]+") || !ids.add(id))
            invalid("id", "Invalid or duplicate conversation");
          choice(row.get("listen"), "listen", "mentions", "all");
        }
        return config;
      }

      public ObjectNode validatePolicy(JsonNode input) {
        keys(input, "webhook policy", "allowed", "bots", "admission");
        return SourceKind.super.validatePolicy(input);
      }

      public JsonNode fields() {
        return array(
            List.of(
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
                            "key",
                            "id",
                            "type",
                            "text",
                            "label",
                            "Conversation",
                            "pattern",
                            "[a-z0-9-]+",
                            "choose",
                            "Choose conversation"),
                        object(
                            "key",
                            "listen",
                            "type",
                            "select",
                            "label",
                            "Receive",
                            "options",
                            List.of(
                                object("value", "mentions", "label", "Mentions"),
                                object("value", "all", "label", "All"))))),
                object(
                    "key",
                    "pollSeconds",
                    "type",
                    "number",
                    "label",
                    "Poll seconds",
                    "min",
                    10,
                    "max",
                    3600,
                    "advanced",
                    true),
                object("key", "endpoint", "type", "text", "label", "Queue endpoint")));
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
                    "Allowed queue actors",
                    "columns",
                    List.of(object("key", "id", "type", "text", "label", "Actor ID")))));
      }

      public JsonNode policyRows(JsonNode config) {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode conversation : config.path("conversations")) {
          boolean all = conversation.path("listen").asText().equals("all");
          rows.add(
              object(
                  "id",
                  conversation.path("id"),
                  "label",
                  conversation.path("id"),
                  "cells",
                  object(
                      "listedUnaddressed",
                      all,
                      "unlistedAddressed",
                      true,
                      "unlistedUnaddressed",
                      all)));
        }
        return array(rows);
      }

      public JsonNode environment(JsonNode config, JsonNode setup) {
        return object(
            "variables",
            List.of(
                object(
                    "key",
                    "DSH_SUPERAGENT_WEBHOOK_CONVERSATION",
                    "description",
                    object("zh", "原始对话", "en", "Origin conversation"))));
      }

      public SourceInstance create(
          JsonNode instance, JsonNode policy, Map<String, String> environment) {
        return null;
      }
    };
  }

  ManagementHost host() {
    return new ManagementHost() {
      public JsonNode setup() {
        return object("problem", null, "feishuProblem", null, "accounts", object());
      }

      public JsonNode health() {
        return object("webhook", object("state", "ready", "detail", "Fixture ready"));
      }
    };
  }

  @Test
  void registeredKindGetsDefaultsValidationRevisionPolicyAndGeneratedForms() throws Exception {
    try (SqliteStore store = new SqliteStore(directory.resolve("state.sqlite"))) {
      Engine engine = new Engine(store);
      SourceKinds kinds = SourceKinds.builtins().register(queueKind());
      ManagementHost host = host();
      VersionedSettings settings = new VersionedSettings(store, engine, directory, host, kinds);
      PromptService prompts = new PromptService(store, settings, host);
      assertTrue(settings.sourceIds().contains("webhook"));
      assertTrue(settings.value("source:webhook").path("enabled").asBoolean());
      assertTrue(settings.value("source:webhook").path("config").path("conversations").isEmpty());
      assertEquals(30, settings.value("source:webhook").path("config").path("pollSeconds").asInt());

      try (ManagementServer server =
          new ManagementServer(engine, store, settings, prompts, host, 0)) {
        server.start();
        HttpClient client = HttpClient.newHttpClient();
        URI endpoint = URI.create(server.url() + "api/settings/source%3Awebhook");
        ObjectNode value = (ObjectNode) settings.value("source:webhook");
        ((ObjectNode) value.path("config"))
            .set("conversations", array(List.of(object("id", "engineering", "listen", "all"))));
        var request =
            HttpRequest.newBuilder(endpoint)
                .header("x-orchestrator-request", "1")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        object("value", value, "revision", 0).toString()))
                .build();
        assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(409, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        ((ObjectNode) value.path("config")).put("pollSeconds", 9);
        var bad =
            HttpRequest.newBuilder(endpoint)
                .header("x-orchestrator-request", "1")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        object("value", value, "revision", 1).toString()))
                .build();
        assertEquals(400, client.send(bad, HttpResponse.BodyHandlers.ofString()).statusCode());

        ManagementViews views = new ManagementViews(engine, store, settings, host);
        JsonNode data = views.settings();
        JsonNode source = findSource(data.path("sources"), "webhook");
        assertEquals("Webhook queue", source.path("title").path("en").asText());
        assertEquals(3, source.path("fields").size());
        assertEquals("engineering", source.path("policy").path("rows").get(0).path("id").asText());
        assertTrue(
            source
                .path("policy")
                .path("rows")
                .get(0)
                .path("cells")
                .path("unlistedUnaddressed")
                .asBoolean());
        assertTrue(
            data.path("environment")
                .path("variables")
                .toString()
                .contains("DSH_SUPERAGENT_WEBHOOK_CONVERSATION"));
        assertEquals("ready", findSource(node(views.sources()), "webhook").path("state").asText());
      }

      ObjectNode policy = (ObjectNode) settings.value("policy");
      ((ObjectNode) policy.path("sources"))
          .set(
              "webhook",
              object(
                  "allowed",
                  List.of(object("id", "queue:Alice")),
                  "admission",
                  object("engineering", object("unlistedAddressed", "inferred"))));
      settings.save("policy", policy, 0, "panel");
      assertTrue(engine.policy().sources().get("webhook").caseInsensitiveActors());
      assertTrue(engine.policy().sources().get("webhook").allowedActors().contains("queue:Alice"));
      ObjectNode changed = (ObjectNode) settings.value("source:webhook");
      ((ObjectNode) changed.path("config")).put("endpoint", "other");
      assertThrows(ApiException.class, () -> settings.save("source:webhook", changed, 1, "tool"));
      ((ObjectNode) changed.path("config")).put("endpoint", "fixture").put("pollSeconds", 60);
      settings.save("source:webhook", changed, 1, "tool");
      assertEquals(2, settings.revision("source:webhook"));
    }
  }

  @Test
  void absentPluginKeepsStoredSourceAndPolicyButRejectsSourceEdits() {
    try (SqliteStore store = new SqliteStore(directory.resolve("state.sqlite"))) {
      Engine engine = new Engine(store);
      ManagementHost host = host();
      VersionedSettings installed =
          new VersionedSettings(
              store, engine, directory, host, SourceKinds.builtins().register(queueKind()));
      ObjectNode value = (ObjectNode) installed.value("source:webhook");
      ((ObjectNode) value.path("config")).put("pollSeconds", 90);
      installed.save("source:webhook", value, 0, "panel");
      VersionedSettings absent =
          new VersionedSettings(store, engine, directory, host, SourceKinds.builtins());
      assertEquals(90, absent.value("source:webhook").path("config").path("pollSeconds").asInt());
      JsonNode row =
          findSource(
              new ManagementViews(engine, store, absent, host).settings().path("sources"),
              "webhook");
      assertFalse(row.path("installed").asBoolean());
      assertTrue(row.path("fields").isEmpty());
      assertEquals(
          "missing",
          findSource(node(new ManagementViews(engine, store, absent, host).sources()), "webhook")
              .path("state")
              .asText());
      assertThrows(ApiException.class, () -> absent.save("source:webhook", value, 1, "panel"));
      ObjectNode policy = (ObjectNode) absent.value("policy");
      ((ObjectNode) policy.path("sources"))
          .set(
              "webhook",
              object("allowed", List.of(object("id", "queue:Alice")), "legacyField", "retained"));
      assertEquals(
          "retained",
          absent
              .validate("policy", policy)
              .path("sources")
              .path("webhook")
              .path("legacyField")
              .asText());
      VersionedSettings reinstalled =
          new VersionedSettings(
              store, engine, directory, host, SourceKinds.builtins().register(queueKind()));
      assertEquals(
          90, reinstalled.value("source:webhook").path("config").path("pollSeconds").asInt());
    }
  }

  @Test
  void invalidStoredPluginConfigurationDisablesInstanceWithoutOverwritingEvidence() {
    try (SqliteStore store = new SqliteStore(directory.resolve("state.sqlite"))) {
      Engine engine = new Engine(store);
      SourceKinds kinds = SourceKinds.builtins().register(queueKind());
      store.putState(
          "settings:block",
          "source:webhook",
          object(
              "block",
              "source:webhook",
              "revision",
              4,
              "value",
              object("enabled", true, "config", object("pollSeconds", 1))));
      VersionedSettings settings = new VersionedSettings(store, engine, directory, host(), kinds);
      assertFalse(settings.value("source:webhook").path("enabled").asBoolean());
      assertEquals(30, settings.value("source:webhook").path("config").path("pollSeconds").asInt());
      assertTrue(settings.block("source:webhook").has("configurationError"));
      assertEquals(
          1,
          store
              .getState("settings:block", "source:webhook", JsonNode.class)
              .path("value")
              .path("config")
              .path("pollSeconds")
              .asInt());
      ObjectNode repaired = (ObjectNode) settings.value("source:webhook");
      repaired.put("enabled", true);
      settings.save("source:webhook", repaired, 4, "panel");
      assertTrue(settings.value("source:webhook").path("enabled").asBoolean());
      assertFalse(settings.block("source:webhook").has("configurationError"));
      assertEquals(
          1,
          settings
              .history(0, 10, "source:webhook")
              .path("items")
              .get(0)
              .path("before")
              .path("config")
              .path("pollSeconds")
              .asInt());
    }
  }

  private static JsonNode findSource(JsonNode rows, String id) {
    for (JsonNode row : rows) if (id.equals(row.path("id").asText())) return row;
    throw new AssertionError("Source not rendered: " + id);
  }
}
