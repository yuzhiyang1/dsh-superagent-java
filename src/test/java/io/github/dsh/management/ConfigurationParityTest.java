package io.github.dsh.management;

import static io.github.dsh.config.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsh.config.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationParityTest {
  @TempDir Path directory;
  SqliteStore store;
  Engine engine;
  VersionedSettings settings;
  PromptService prompts;
  ManagementHost host = new ManagementHost() {};

  @BeforeEach
  void start() {
    store = new SqliteStore(directory.resolve("state.sqlite"));
    engine = new Engine(store);
    settings = new VersionedSettings(store, engine, directory, host);
    prompts = new PromptService(store, settings, host);
  }

  @AfterEach
  void close() {
    store.close();
  }

  @Test
  void defaultsAndSourceLimitsMatchPinnedProject() {
    assertEquals(15000, settings.value("deployment").path("admissionContextTimeoutMs").asInt());
    assertEquals(60, settings.value("source:github").path("config").path("pollSeconds").asInt());
    assertEquals(
        3, settings.value("source:github").path("config").path("failuresBeforeError").asInt());
    assertEquals(20, settings.value("source:feishu").path("config").path("readPageSize").asInt());
    assertEquals(
        5000, settings.value("source:feishu").path("config").path("linkTimeoutMs").asInt());
    assertDoesNotThrow(
        () ->
            settings.validate(
                "executor", object("blockedCommands", List.of("sh", "bash", "personal-gh"))));
    assertThrows(
        ApiException.class,
        () -> settings.validate("executor", object("blockedCommands", List.of("git"))));
    assertThrows(
        ApiException.class,
        () -> settings.validate("source:github", object("config", object("pollSeconds", 59))));
    assertThrows(
        ApiException.class,
        () -> settings.validate("source:feishu", object("config", object("readPageSize", 51))));
  }

  @Test
  void sourceWritesRequireTrustedPanelAndPersistAcrossReload() {
    for (String id : List.of("github", "feishu")) {
      String block = "source:" + id;
      assertFalse(settings.value(block).path("config").path("writesEnabled").asBoolean());
      ObjectNode config = (ObjectNode) settings.value(block).deepCopy();
      ((ObjectNode) config.path("config")).put("writesEnabled", true);
      assertThrows(ApiException.class, () -> settings.save(block, config, 0, "tool"));
      assertEquals(0, settings.revision(block));
      settings.save(block, config, 0, "panel");
      assertTrue(settings.value(block).path("config").path("writesEnabled").asBoolean());
      assertThrows(
          ApiException.class,
          () -> settings.validate(block, object("config", object("writesEnabled", "true"))));
    }
    try (SqliteStore reopened = new SqliteStore(directory.resolve("state.sqlite"))) {
      VersionedSettings s = new VersionedSettings(reopened, new Engine(reopened), directory, host);
      assertTrue(s.value("source:github").path("config").path("writesEnabled").asBoolean());
      assertTrue(s.value("source:feishu").path("config").path("writesEnabled").asBoolean());
    }
  }

  @Test
  void githubNamesFoldButFeishuNamesStayCaseSensitive() {
    JsonNode feishu =
        object(
            "sources",
            object(
                "feishu",
                object(
                    "allowed",
                    List.of(object("id", "ou_ABC"), object("id", "ou_abc")),
                    "bots",
                    List.of(object("id", "cli_App")),
                    "admission",
                    object())));
    assertDoesNotThrow(() -> settings.validate("policy", feishu));
    JsonNode github =
        object(
            "sources",
            object(
                "github",
                object("allowed", List.of(object("id", "Alice"), object("id", "alice")))));
    assertThrows(ApiException.class, () -> settings.validate("policy", github));
    assertThrows(
        ApiException.class,
        () ->
            settings.validate(
                "policy",
                object(
                    "sources",
                    object("feishu", object("allowed", List.of(object("id", "alice")))))));
    assertThrows(
        ApiException.class,
        () ->
            settings.validate(
                "policy",
                object(
                    "sources",
                    object(
                        "github",
                        object(
                            "admission",
                            object("issues", object("unlistedUnaddressed", "explicit")))))));
  }

  @Test
  void blockRevisionsIncrementIndependentlyAndNoopCreatesNoVersion() {
    settings.save("eval", object("mode", "auto"), 0, "panel");
    settings.save("executor", object("maxWorkers", 4), 0, "panel");
    assertEquals(1, settings.revision("eval"));
    assertEquals(1, settings.revision("executor"));
    assertEquals(2, settings.currentRevision());
    settings.save("executor", settings.value("executor"), 1, "panel");
    assertEquals(2, settings.currentRevision());
    assertEquals(1, settings.revision("executor"));
    assertEquals(2, settings.history(0, 100, null).path("items").size());
  }

  @Test
  void settingsAndPromptsReopenFromSameDurableDatabase() {
    settings.save("executor", object("maxWorkers", 7), 0, "panel");
    ObjectNode c = prompts.defaults();
    c.put("scope", "Team-owned scope");
    prompts.save(c, 0, "panel");
    try (SqliteStore reopened = new SqliteStore(directory.resolve("state.sqlite"))) {
      VersionedSettings s = new VersionedSettings(reopened, new Engine(reopened), directory, host);
      PromptService p = new PromptService(reopened, s, host);
      assertEquals(7, s.value("executor").path("maxWorkers").asInt());
      assertEquals(1, p.revision());
      assertEquals("Team-owned scope", p.current().path("config").path("scope").asText());
    }
  }

  @Test
  void panelNoticesCoalesceTransactionallyAndToolsDoNotEcho() {
    settings.save("eval", object("mode", "auto"), 0, "panel");
    settings.save("eval", object("mode", "off"), 1, "panel");
    List<Effect> notices =
        store.effects().stream()
            .filter(e -> e.kind() == EffectKind.NOTICE && e.key().equals("settings:eval"))
            .toList();
    assertEquals(1, notices.size());
    assertEquals("2", notices.getFirst().payload().get("revision"));
    assertEquals("pending", settings.deliveryState("settings:eval"));
    settings.save("executor", object("maxWorkers", 4), 0, "tool");
    assertEquals("injected", settings.deliveryState("settings:executor"));
    ObjectNode c = prompts.defaults();
    c.put("scope", "First edit");
    prompts.save(c, 0, "panel");
    c.put("scope", "Second edit");
    prompts.save(c, 1, "panel");
    assertEquals(
        1,
        store.effects().stream()
            .filter(e -> e.kind() == EffectKind.NOTICE && e.key().equals("prompts"))
            .count());
    assertTrue(prompts.view(null, 0).path("notificationPending").asBoolean());
    Effect prompt =
        store.effects().stream().filter(e -> e.key().equals("prompts")).findFirst().orElseThrow();
    store.completeEffect(prompt.id());
    assertFalse(prompts.view(null, 0).path("notificationPending").asBoolean());
  }

  @Test
  void promptDriftPreservesOldDefaultKeepMineAndUseNew() {
    JsonNode part = prompts.view(null, 0).path("parts").get(0);
    String key = part.path("key").asText(), oldBase = "old-builtin-hash";
    store.putState("prompts:builtin", oldBase, "Old built-in text");
    ObjectNode c = prompts.defaults();
    c.set(
        "overrides",
        object(key, object("text", "Team override survives upgrades", "base", oldBase)));
    prompts.save(c, 0, "panel");
    JsonNode drift = prompts.view(null, 0).path("defaultChanged").get(0);
    assertEquals("Old built-in text", drift.path("previous").asText());
    assertEquals(
        "Team override survives upgrades",
        prompts.current().path("config").path("overrides").path(key).path("text").asText());
    ((ObjectNode) c.path("overrides").path(key)).put("base", part.path("base").asText());
    prompts.save(c, 1, "panel");
    assertTrue(prompts.defaultChanged(prompts.current().path("config")).isEmpty());
    assertEquals(
        part.path("text").asText(),
        store.getState("prompts:builtin", part.path("base").asText(), String.class));
    ((ObjectNode) c.path("overrides").path(key)).put("text", part.path("text").asText());
    prompts.save(c, 2, "panel");
    assertFalse(prompts.current().path("config").has("overrides"));
    assertEquals(4, prompts.history(0, 20).path("revisions").size());
  }

  @Test
  void removedPreviouslySavedPartsDropButNewUnknownPartsAreRejected() {
    ObjectNode c = prompts.defaults();
    c.set("overrides", object("runtime/removed", object("text", "legacy text", "base", "old")));
    store.putState(
        "prompts:version",
        "1",
        object(
            "revision",
            1,
            "previousRevision",
            0,
            "savedAt",
            Instant.now().toString(),
            "source",
            "panel",
            "config",
            c));
    store.putState("prompts", "revision", 1);
    assertTrue(prompts.defaultChanged(c).getFirst().path("removed").asBoolean());
    assertFalse(prompts.validate(c).has("overrides"));
    ((ObjectNode) c.path("overrides"))
        .set("runtime/new-unknown", object("text", "unauthorized new key"));
    assertThrows(ApiException.class, () -> prompts.validate(c));
  }

  @Test
  void omittedOverrideBaseRetainsPriorBaseAndTemplatesFillExactlyOnce() {
    ObjectNode c = prompts.defaults();
    c.set(
        "overrides",
        object("runtime/environment", object("text", "ENV {variables}", "base", "legacy")));
    prompts.save(c, 0, "panel");
    ((ObjectNode) c.path("overrides").path("runtime/environment")).remove("base");
    assertEquals(
        "legacy",
        prompts.validate(c).path("overrides").path("runtime/environment").path("base").asText());
    String assembled =
        prompts.compose(
            prompts.validate(c), "worker", List.of(Map.of("key", "NAME", "value", "{mode}")));
    assertTrue(assembled.endsWith("ENV NAME={mode}"));
    assertFalse(assembled.contains("## Policy"));
    ObjectNode bad = prompts.defaults();
    bad.set("overrides", object("runtime/policy", object("text", "Policy {version} {mode}")));
    assertThrows(ApiException.class, () -> prompts.validate(bad));
  }

  @Test
  void historyPaginationAndSizeLimitsNeverTruncate() {
    for (int n = 0; n < 25; n++) {
      ObjectNode c = prompts.defaults();
      c.put("scope", "revision " + n);
      prompts.save(c, n, "tool");
    }
    assertEquals(20, prompts.history(0, 20).path("revisions").size());
    assertEquals(20, prompts.history(0, 20).path("next_offset").asInt());
    assertEquals(6, prompts.history(20, 20).path("revisions").size());
    assertTrue(prompts.history(20, 20).path("next_offset").isNull());
    settings.save("deployment", object("promptMaxChars", 1000), 0, "panel");
    ObjectNode c =
        object("communication", "x".repeat(1001), "scope", "", "delivery", "", "skills", object());
    assertThrows(ApiException.class, () -> prompts.validate(c));
    assertEquals(25, prompts.revision());
  }

  @Test
  void settingsMigrationIsAtomicIdempotentAndRejectsNewerSchemas() {
    store.putState("configuration:schema", "settings", 1);
    store.putState(
        "settings:block",
        "source:github",
        object(
            "block",
            "source:github",
            "revision",
            3,
            "value",
            object("enabled", true, "config", object("pollIntervalSeconds", 120))));
    ConfigurationMigrations.settings(store);
    assertEquals(120, settings.value("source:github").path("config").path("pollSeconds").asInt());
    assertFalse(settings.value("source:github").path("config").has("pollIntervalSeconds"));
    assertEquals(3, settings.revision("source:github"));
    ConfigurationMigrations.settings(store);
    assertEquals(3, settings.revision("source:github"));
    store.putState("configuration:schema", "settings", 999);
    assertThrows(
        IllegalStateException.class, () -> new VersionedSettings(store, engine, directory, host));
  }

  @Test
  void legacyPromptMigrationPreservesRawInputAndNormalizesOnce() {
    store.putState("configuration:schema", "prompts", 1);
    ObjectNode legacy =
        object(
            "shared",
            "old",
            "orchestrator",
            "old",
            "worker",
            "old",
            "guides",
            object(
                "identities",
                "Use task/<id> and DSH_GO_NAME",
                "work",
                "fewer than 42 lines (additions plus deletions)"));
    store.putState(
        "prompts:version",
        "1",
        object(
            "revision",
            1,
            "previousRevision",
            0,
            "savedAt",
            "",
            "source",
            "web",
            "config",
            legacy));
    store.putState("prompts", "revision", 1);
    ConfigurationMigrations.prompts(store, prompts.defaults());
    ObjectNode migrated = prompts.version(1);
    assertEquals(legacy, migrated.path("legacy").path("config"));
    assertEquals(
        "Use ticket/<id> and DSH_SUPERAGENT_NAME",
        migrated.path("config").path("skills").path("identities").asText());
    assertTrue(migrated.path("config").path("delivery").asText().contains("fewer than 42 lines"));
    ConfigurationMigrations.prompts(store, prompts.defaults());
    assertEquals(migrated, prompts.version(1));
  }
}
