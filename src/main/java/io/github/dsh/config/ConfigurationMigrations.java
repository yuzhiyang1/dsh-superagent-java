package io.github.dsh.config;

import static io.github.dsh.config.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsh.core.SqliteStore;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/** 配置有独立的存储版本，升级只运行一次且整个步骤在事务中。 这是 Java 状态库迁移，不把上游 TypeScript 数据库误认作本格式。 */
public final class ConfigurationMigrations {
  private ConfigurationMigrations() {}

  public static final int SETTINGS_SCHEMA = 2, PROMPTS_SCHEMA = 2;

  public static void settings(SqliteStore store) {
    store.atomic(
        () -> {
          int version = version(store, "settings");
          if (version > SETTINGS_SCHEMA)
            throw new IllegalStateException(
                "Settings schema "
                    + version
                    + " is newer than this program; restore the pre-upgrade backup");
          if (version == SETTINGS_SCHEMA) return null;
          ObjectNode archived = object();
          for (String block : VersionedSettings.BLOCKS) {
            ObjectNode record = store.getState("settings:block", block, ObjectNode.class);
            if (record == null) continue;
            ObjectNode migrated = record.deepCopy();
            JsonNode value = migrated.path("value");
            if (value.isObject() && block.startsWith("source:")) {
              JsonNode config = value.path("config");
              if (config.isObject()) {
                ObjectNode c = (ObjectNode) config;
                if (block.equals("source:github")) rename(c, "pollIntervalSeconds", "pollSeconds");
                if (block.equals("source:feishu")) rename(c, "pageSize", "readPageSize");
              }
            }
            if (value.isObject() && block.equals("executor")) {
              ObjectNode executor = (ObjectNode) value;
              if (executor.path("prepare").isTextual())
                executor.put("prepare", currentNames(executor.path("prepare").asText()));
            }
            if (!record.equals(migrated)) {
              archived.set(block, record);
              store.putState("settings:block", block, migrated);
            }
          }
          if (!archived.isEmpty())
            store.putState(
                "configuration:migration",
                "settings-" + version + "-to-2",
                object("at", Instant.now().toString(), "previous", archived));
          store.putState("configuration:schema", "settings", SETTINGS_SCHEMA);
          return null;
        });
  }

  public static void prompts(SqliteStore store, ObjectNode defaults) {
    store.atomic(
        () -> {
          int schema = version(store, "prompts");
          if (schema > PROMPTS_SCHEMA)
            throw new IllegalStateException(
                "Prompt schema "
                    + schema
                    + " is newer than this program; restore the pre-upgrade backup");
          if (schema == PROMPTS_SCHEMA) return null;
          Integer latest = store.getState("prompts", "revision", Integer.class);
          ObjectNode readable = defaults.deepCopy();
          if (latest != null)
            for (int revision = 0; revision <= latest; revision++) {
              ObjectNode record =
                  store.getState("prompts:version", Integer.toString(revision), ObjectNode.class);
              if (record == null) continue;
              JsonNode original = record.path("config");
              ObjectNode config;
              boolean legacy = false;
              if (original.isObject()
                  && original.has("shared")
                  && original.has("orchestrator")
                  && original.has("worker")
                  && original.path("guides").isObject()) {
                legacy = true;
                config = defaults.deepCopy();
                ObjectNode skills = object();
                Set<String> builtins =
                    Set.of(
                        "cli",
                        "subscriptions",
                        "replies",
                        "reviews",
                        "dispatch",
                        "work",
                        "feishu",
                        "github");
                original
                    .path("guides")
                    .fields()
                    .forEachRemaining(
                        e -> {
                          if (!builtins.contains(e.getKey()) && e.getValue().isTextual())
                            skills.put(e.getKey(), legacyText(e.getValue().asText()));
                        });
                config.set("skills", skills);
                Matcher threshold =
                    Pattern.compile("fewer than ([0-9]+) lines \\(additions plus deletions\\)")
                        .matcher(original.path("guides").path("work").asText());
                if (threshold.find())
                  config.put(
                      "delivery",
                      config
                          .path("delivery")
                          .asText()
                          .replace(
                              "fewer than 200 lines (additions plus deletions)",
                              "fewer than "
                                  + threshold.group(1)
                                  + " lines (additions plus deletions)"));
              } else if (original.isObject()
                  && original.path("communication").isTextual()
                  && original.path("scope").isTextual()
                  && original.path("delivery").isTextual()
                  && original.path("skills").isObject()) config = (ObjectNode) original.deepCopy();
              else {
                legacy = true;
                config = readable.deepCopy();
              }
              for (String part : PromptService.TEAM)
                config.put(part, currentNames(config.path(part).asText()));
              ObjectNode skills = (ObjectNode) config.path("skills");
              List<String> names = new ArrayList<>();
              skills.fieldNames().forEachRemaining(names::add);
              for (String name : names)
                if (skills.path(name).isTextual())
                  skills.put(name, currentNames(skills.path(name).asText()));
              if (legacy && !record.has("legacy")) record.set("legacy", object("config", original));
              record.set("config", config);
              store.putState("prompts:version", Integer.toString(revision), record);
              readable = config;
            }
          store.putState("configuration:schema", "prompts", PROMPTS_SCHEMA);
          return null;
        });
  }

  private static int version(SqliteStore store, String name) {
    Integer version = store.getState("configuration:schema", name, Integer.class);
    return version == null ? 0 : version;
  }

  private static void rename(ObjectNode object, String from, String to) {
    if (object.has(from)) {
      if (!object.has(to)) object.set(to, object.get(from));
      object.remove(from);
    }
  }

  private static String currentNames(String text) {
    return text.replaceAll("\\bdsh-(?:tag|go|freelancer)-", "dsh-superagent-")
        .replaceAll("\\bDSH_(?:TAG|GO|FREELANCER)_", "DSH_SUPERAGENT_");
  }

  private static String legacyText(String text) {
    return currentNames(text)
        .replace("reply_to_event_id", "reply_to_trigger_id")
        .replaceAll("\\bevent_id\\b", "trigger_id")
        .replaceAll("\\btask_id\\b", "ticket_id")
        .replace("related_tasks", "related_tickets")
        .replaceAll("(?<![\\w/-])event/", "trigger/")
        .replaceAll("(?<![\\w/-])task/", "ticket/");
  }
}
