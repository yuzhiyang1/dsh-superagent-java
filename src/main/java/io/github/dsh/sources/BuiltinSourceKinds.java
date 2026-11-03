package io.github.dsh.sources;

import static io.github.dsh.config.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.util.*;

/** 内置来源和第三方来源使用相同的描述符。界面从字段生成，不识别具体来源类。 */
public final class BuiltinSourceKinds {
  private BuiltinSourceKinds() {}

  private static ObjectNode label(String zh, String en) {
    return object("zh", zh, "en", en);
  }

  private static ObjectNode field(String key, String type, String zh, String en) {
    return object("key", key, "type", type, "label", label(zh, en));
  }

  private static ObjectNode number(String key, String zh, String en, int min, int max) {
    return field(key, "number", zh, en).put("min", min).put("max", max).put("advanced", true);
  }

  private static JsonNode allowed(String pattern) {
    ObjectNode id = field("id", "text", "账号", "Account");
    id.put("pattern", pattern);
    return object(
        "key",
        "allowed",
        "type",
        "rows",
        "label",
        label("可发起工作的账号", "Allowed accounts"),
        "columns",
        List.of(id, field("name", "text", "名字", "Name").put("optional", true)));
  }

  private static JsonNode row(String id, String zh, String en, boolean observed) {
    return object(
        "id",
        id,
        "label",
        label(zh, en),
        "note",
        label(observed ? "全部" : "仅 @", observed ? "All" : "@ only"),
        "cells",
        object(
            "listedUnaddressed",
            observed,
            "unlistedAddressed",
            true,
            "unlistedUnaddressed",
            observed));
  }

  private abstract static class Builtin implements SourceKind {
    public boolean builtinValidation() {
      return true;
    }

    public ObjectNode validateConfig(JsonNode value) {
      if (!value.isObject()) throw new IllegalArgumentException("Source config must be an object");
      ObjectNode defaults = defaultConfig();
      value
          .fieldNames()
          .forEachRemaining(
              key -> {
                if (!defaults.has(key))
                  throw new IllegalArgumentException("Unknown source config key: " + key);
              });
      ObjectNode result = defaults(value, defaults);
      defaults
          .fields()
          .forEachRemaining(
              entry -> {
                JsonNode actual = result.path(entry.getKey()), expected = entry.getValue();
                if (expected.isBoolean() && !actual.isBoolean()
                    || expected.isTextual() && !actual.isTextual()
                    || expected.isIntegralNumber() && !actual.isIntegralNumber()
                    || expected.isArray() && !actual.isArray())
                  throw new IllegalArgumentException(
                      "Invalid source config type: " + entry.getKey());
              });
      return result;
    }

    public ObjectNode validatePolicy(JsonNode value) {
      if (!value.isObject()) throw new IllegalArgumentException("Source policy must be an object");
      Set<String> keys =
          id().equals("feishu")
              ? Set.of("allowed", "bots", "admission", "syncChat")
              : Set.of("allowed", "bots", "admission");
      value
          .fieldNames()
          .forEachRemaining(
              key -> {
                if (!keys.contains(key))
                  throw new IllegalArgumentException("Unknown policy key: " + key);
              });
      return SourceKind.super.validatePolicy(value);
    }

    public boolean canRefreshLabels() {
      return true;
    }

    public Set<String> panelOnlyKeys() {
      return Set.of(
          "cli",
          "repository",
          "botLogin",
          "botId",
          "appId",
          "apiBase",
          "tokenEnvironment",
          "writesEnabled");
    }

    public Set<String> restartKeys() {
      return Set.of(
          "cli", "repository", "botLogin", "botId", "appId", "apiBase", "tokenEnvironment");
    }

    public SourceInstance create(
        JsonNode instance, JsonNode policy, Map<String, String> environment) {
      return createBuiltin(id(), instance, policy, environment);
    }
  }

  public static SourceKind github() {
    return new Builtin() {
      public String id() {
        return "github";
      }

      public JsonNode title() {
        return label("GitHub", "GitHub");
      }

      public JsonNode description() {
        return label(
            "接收仓库通知、讨论、代码审查与 CI。",
            "Receive repository notifications, discussions, reviews and CI.");
      }

      public boolean channelAllowed(Map<String, String> address, JsonNode config) {
        return config
            .path("repository")
            .asText()
            .equalsIgnoreCase(
                address.getOrDefault("repository", config.path("repository").asText()));
      }

      public JsonNode channelLink(Map<String, String> address, JsonNode config) {
        String number = address.getOrDefault("number", address.getOrDefault("issue", ""));
        return object(
            "url",
            address.getOrDefault(
                "url",
                "https://github.com/" + config.path("repository").asText() + "/issues/" + number),
            "label",
            label("GitHub #" + number, "GitHub #" + number));
      }

      public boolean caseInsensitiveActors() {
        return true;
      }

      public Scope scope(io.github.dsh.core.Model.Trigger trigger, JsonNode c) {
        String row =
            trigger.metadata().containsKey("branch")
                ? "ci"
                : Boolean.TRUE.equals(trigger.metadata().get("ci"))
                    ? null
                    : Objects.toString(trigger.metadata().get("kind"), "").equals("issue")
                        ? "issues"
                        : Objects.toString(trigger.metadata().get("kind"), "").equals("pull")
                            ? "pulls"
                            : "comments";
        String key =
            row == null
                ? ""
                : switch (row) {
                  case "issues" -> "observeIssues";
                  case "pulls" -> "observePulls";
                  case "ci" -> "observeCi";
                  default -> "observeComments";
                };
        return new Scope(true, c.path(key).asBoolean(), row, null);
      }

      public List<String> refreshLabels(SourceContext ctx, SourceInstance source) throws Exception {
        ctx.store().putState("labels", "github", source.read(ctx, "repository"));
        return List.of("github");
      }

      public ObjectNode defaultConfig() {
        return object(
            "repository",
            "",
            "cli",
            "",
            "writesEnabled",
            false,
            "observeIssues",
            false,
            "observePulls",
            false,
            "observeComments",
            false,
            "observeCi",
            false,
            "pollSeconds",
            60,
            "failuresBeforeError",
            3,
            "pageSize",
            50,
            "botLogin",
            "",
            "apiBase",
            "https://api.github.com",
            "tokenEnvironment",
            "GITHUB_TOKEN");
      }

      public JsonNode fields() {
        return array(
            List.of(
                field("repository", "text", "仓库", "Repository"),
                field("botLogin", "text", "机器人账号", "Bot login"),
                field("cli", "text", "CLI 包装器", "CLI wrapper"),
                field("writesEnabled", "switch", "允许发送", "Enable writes"),
                field("observeIssues", "switch", "接收新 Issue", "Observe issues"),
                field("observePulls", "switch", "接收新 PR", "Observe pull requests"),
                field("observeComments", "switch", "接收未提及评论", "Observe unaddressed comments"),
                field("observeCi", "switch", "接收默认分支 CI", "Observe default branch CI"),
                number("pollSeconds", "轮询间隔 / 秒", "Poll seconds", 60, 3600),
                number("failuresBeforeError", "报告错误前的连续失败", "Failures before error", 1, 20),
                number("pageSize", "读取条数", "Page size", 1, 100),
                field("apiBase", "text", "API 地址", "API base").put("advanced", true),
                field("tokenEnvironment", "text", "令牌环境变量名", "Token environment name")
                    .put("advanced", true)));
      }

      public JsonNode policyFields() {
        return array(
            List.of(
                allowed("[A-Za-z0-9-]+"),
                object(
                    "key",
                    "bots",
                    "type",
                    "rows",
                    "label",
                    label("允许评估的机器人", "Admitted bots"),
                    "columns",
                    List.of(
                        field("id", "text", "账号", "Account")
                            .put("pattern", "[A-Za-z0-9-]+(\\[bot\\])?")))));
      }

      public JsonNode policyRows(JsonNode c) {
        return array(
            List.of(
                row("issues", "新 Issue", "New issues", c.path("observeIssues").asBoolean()),
                row("pulls", "新 PR", "New pull requests", c.path("observePulls").asBoolean()),
                row("comments", "评论", "Comments", c.path("observeComments").asBoolean()),
                object(
                    "id",
                    "ci",
                    "label",
                    label("默认分支 CI", "Default branch CI"),
                    "cells",
                    object(
                        "listedUnaddressed",
                        false,
                        "unlistedAddressed",
                        false,
                        "unlistedUnaddressed",
                        c.path("observeCi").asBoolean()))));
      }

      public Map<String, String> environmentValues(
          JsonNode c, JsonNode setup, io.github.dsh.core.Model.Trigger trigger) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("DSH_SUPERAGENT_GITHUB_REPOSITORY", c.path("repository").asText());
        result.put("DSH_SUPERAGENT_GITHUB_ACCOUNT", c.path("botLogin").asText());
        if (trigger != null && trigger.source().equals(id())) {
          result.put(
              "DSH_SUPERAGENT_GITHUB_NUMBER",
              Objects.toString(trigger.metadata().get("number"), ""));
          result.put(
              "DSH_SUPERAGENT_GITHUB_PR_HEAD",
              Objects.toString(trigger.metadata().get("head"), ""));
        }
        return result;
      }

      public JsonNode facts(JsonNode setup) {
        return array(
            List.of(
                object(
                    "key",
                    "account",
                    "label",
                    label("账号", "Account"),
                    "value",
                    setup.path("accounts").path("github").path("login").asText())));
      }

      public JsonNode environment(JsonNode c, JsonNode setup) {
        return object(
            "variables",
            List.of(
                object(
                    "key",
                    "DSH_SUPERAGENT_GITHUB_REPOSITORY",
                    "value",
                    c.path("repository").asText(),
                    "description",
                    label("接收的仓库", "Source repository")),
                object(
                    "key",
                    "DSH_SUPERAGENT_GITHUB_ACCOUNT",
                    "value",
                    c.path("botLogin").asText(),
                    "description",
                    label("智能体账号", "Agent account"))));
      }
    };
  }

  public static SourceKind feishu() {
    return new Builtin() {
      public String id() {
        return "feishu";
      }

      public JsonNode policySync() {
        return object("label", label("同步群成员", "Sync chat members"), "field", "syncChat");
      }

      public boolean channelAllowed(Map<String, String> address, JsonNode c) {
        if (Boolean.parseBoolean(address.getOrDefault("direct", "false")))
          return c.path("directMessages").asBoolean();
        for (JsonNode chat : c.path("chats"))
          if (chat.path("chatId").asText().equals(address.get("chatId"))) return true;
        return c.path("otherChats").asText().equals("mentions");
      }

      public JsonNode channelLink(Map<String, String> address, JsonNode config) {
        return object(
            "url", address.getOrDefault("url", ""), "label", label("飞书对话", "Feishu conversation"));
      }

      public boolean syncReady(JsonNode c, JsonNode policy) {
        return !policy.path("syncChat").asText().isBlank();
      }

      public Map<String, String> environmentValues(
          JsonNode c, JsonNode setup, io.github.dsh.core.Model.Trigger trigger) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("DSH_SUPERAGENT_FEISHU_BOT", c.path("botId").asText());
        if (trigger != null && trigger.source().equals(id()))
          values.put(
              "DSH_SUPERAGENT_FEISHU_CHAT", Objects.toString(trigger.metadata().get("chatId"), ""));
        return values;
      }

      public Scope scope(io.github.dsh.core.Model.Trigger trigger, JsonNode c) {
        boolean direct = Boolean.TRUE.equals(trigger.metadata().get("direct"));
        if (direct)
          return new Scope(
              c.path("directMessages").asBoolean(), false, "direct", "direct_disabled");
        String chat = Objects.toString(trigger.metadata().get("chatId"), "");
        for (JsonNode row : c.path("chats"))
          if (row.path("chatId").asText().equals(chat))
            return new Scope(true, row.path("listen").asText().equals("all"), chat, null);
        return new Scope(
            c.path("otherChats").asText().equals("mentions"), false, "other", "chat_not_listened");
      }

      public List<String> refreshLabels(SourceContext ctx, SourceInstance source) throws Exception {
        return List.copyOf(((FeishuSource) source).refreshChatNames(ctx).keySet());
      }

      public ObjectNode synchronizePolicy(
          SourceContext ctx, SourceInstance source, JsonNode current) throws Exception {
        ObjectNode value = validatePolicy(current);
        Map<String, String> members = ((FeishuSource) source).readMembers();
        value.set(
            "allowed",
            array(
                members.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> object("id", e.getKey(), "name", e.getValue()))
                    .toList()));
        return value;
      }

      public boolean defaultEnabled() {
        return false;
      }

      public JsonNode title() {
        return label("飞书", "Feishu");
      }

      public JsonNode description() {
        return label("接收配置群聊、私聊和话题。", "Receive configured chats, direct messages and topics.");
      }

      public ObjectNode defaultConfig() {
        return object(
            "cli",
            "",
            "writesEnabled",
            false,
            "chats",
            List.of(),
            "directMessages",
            true,
            "otherChats",
            "none",
            "readPageSize",
            20,
            "linkTimeoutMs",
            5000,
            "botId",
            "",
            "appId",
            "",
            "apiBase",
            "https://open.feishu.cn/open-apis/",
            "tokenEnvironment",
            "FEISHU_TENANT_ACCESS_TOKEN");
      }

      public JsonNode fields() {
        return array(
            List.of(
                field("cli", "text", "CLI 包装器", "CLI wrapper"),
                field("botId", "text", "机器人 open_id", "Bot open_id"),
                field("appId", "text", "应用 ID", "App ID"),
                field("writesEnabled", "switch", "允许发送", "Enable writes"),
                object(
                    "key",
                    "chats",
                    "type",
                    "rows",
                    "label",
                    label("接收的群", "Received chats"),
                    "columns",
                    List.of(
                        field("chatId", "text", "群 ID", "Chat ID")
                            .put("pattern", "oc_[A-Za-z0-9]+"),
                        field("name", "text", "名字", "Name").put("optional", true),
                        object(
                            "key",
                            "listen",
                            "type",
                            "select",
                            "label",
                            label("接收", "Receive"),
                            "options",
                            List.of(
                                object("value", "mentions", "label", label("仅 @", "@ only")),
                                object("value", "all", "label", label("全部", "All")))))),
                field("directMessages", "switch", "接收私聊", "Receive direct messages"),
                object(
                    "key",
                    "otherChats",
                    "type",
                    "select",
                    "label",
                    label("其他群", "Other chats"),
                    "options",
                    List.of(
                        object("value", "none", "label", label("不接收", "None")),
                        object("value", "mentions", "label", label("仅 @", "@ only")))),
                number("readPageSize", "话题读取条数", "Thread page size", 1, 50),
                number("linkTimeoutMs", "链接超时 / 毫秒", "Link timeout ms", 1, 90000),
                field("apiBase", "text", "API 地址", "API base").put("advanced", true),
                field("tokenEnvironment", "text", "令牌环境变量名", "Token environment name")
                    .put("advanced", true)));
      }

      public JsonNode policyFields() {
        return array(
            List.of(
                allowed("ou_[A-Za-z0-9]+"),
                field("syncChat", "text", "同步成员的群", "Membership sync chat"),
                object(
                    "key",
                    "bots",
                    "type",
                    "rows",
                    "label",
                    label("允许评估的机器人", "Admitted bots"),
                    "columns",
                    List.of(
                        field("id", "text", "App ID 或 open_id", "App ID or open_id")
                            .put("pattern", "(cli|ou)_[A-Za-z0-9]+")))));
      }

      public JsonNode policyRows(JsonNode c) {
        ArrayNode rows = array(List.of());
        if (c.path("directMessages").asBoolean())
          rows.add(row("direct", "私聊", "Direct messages", false));
        for (JsonNode chat : c.path("chats"))
          rows.add(
              row(
                  chat.path("chatId").asText(),
                  chat.path("name").asText(chat.path("chatId").asText()),
                  chat.path("name").asText(chat.path("chatId").asText()),
                  chat.path("listen").asText().equals("all")));
        if (c.path("otherChats").asText().equals("mentions"))
          rows.add(row("other", "其他群", "Other chats", false));
        return rows;
      }

      public JsonNode environment(JsonNode c, JsonNode setup) {
        return object(
            "variables",
            List.of(
                object(
                    "key",
                    "DSH_SUPERAGENT_FEISHU_BOT",
                    "value",
                    c.path("botId").asText(),
                    "description",
                    label("机器人 open_id", "Bot open_id"))));
      }
    };
  }

  private static SourceInstance createBuiltin(
      String id, JsonNode value, JsonNode policy, Map<String, String> environment) {
    JsonNode c = value.path("config");
    boolean enabled = value.path("enabled").asBoolean(),
        writes = c.path("writesEnabled").asBoolean();
    if (id.equals("github")) {
      String repository = c.path("repository").asText(), bot = c.path("botLogin").asText();
      if (repository.isBlank() || bot.isBlank()) return null;
      return new GitHubSource(
          id,
          URI.create(c.path("apiBase").asText("https://api.github.com/")),
          environment.getOrDefault(c.path("tokenEnvironment").asText("GITHUB_TOKEN"), ""),
          new GitHubSource.Config(
              enabled,
              writes,
              repository,
              bot,
              c.path("observeIssues").asBoolean(),
              c.path("observePulls").asBoolean(),
              c.path("observeComments").asBoolean(),
              c.path("observeCi").asBoolean(),
              c.path("pollSeconds").asInt(60),
              c.path("maxPages").asInt(100),
              c.path("failuresBeforeError").asInt(3),
              c.path("cli").asText("")));
    }
    if (id.equals("feishu")) {
      String bot = c.path("botId").asText(), app = c.path("appId").asText();
      if (bot.isBlank() || app.isBlank()) return null;
      List<FeishuSource.Chat> chats = new ArrayList<>();
      for (JsonNode chat : c.path("chats"))
        chats.add(
            new FeishuSource.Chat(
                chat.path("chatId").asText(chat.path("id").asText()),
                chat.path("name").asText(""),
                chat.path("listen").asText("mentions")));
      return new FeishuSource(
          id,
          URI.create(c.path("apiBase").asText("https://open.feishu.cn/open-apis/")),
          environment.getOrDefault(
              c.path("tokenEnvironment").asText("FEISHU_TENANT_ACCESS_TOKEN"), ""),
          new FeishuSource.Config(
              enabled,
              writes,
              bot,
              app,
              chats,
              c.path("directMessages").asBoolean(true),
              c.path("otherChats").asText("none"),
              c.path("pollSeconds").asInt(60),
              c.path("readPageSize").asInt(20),
              c.path("maxPages").asInt(100),
              10 * 1024 * 1024,
              policy.path("sources").path(id).path("syncChat").asText(""),
              c.path("cli").asText("")));
    }
    throw new IllegalArgumentException("Unknown built-in source kind");
  }
}
