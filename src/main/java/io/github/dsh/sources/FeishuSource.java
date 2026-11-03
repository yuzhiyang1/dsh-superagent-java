package io.github.dsh.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsh.adapters.*;
import io.github.dsh.core.Model.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** 飞书来源：已验证事件接入、断线补拉、订阅话题轮询、原始上下文与图片、群名单同步。 */
public final class FeishuSource implements SourceInstance {
  public record Chat(String chatId, String name, String listen) {
    public Chat {
      FeishuProtocol.safe(chatId, "oc_");
      if (!Set.of("all", "mentions").contains(listen))
        throw new IllegalArgumentException("Chat listen must be all/mentions");
    }
  }

  public record Config(
      boolean enabled,
      boolean writesEnabled,
      String botId,
      String appId,
      List<Chat> chats,
      boolean directMessages,
      String otherChats,
      int pollSeconds,
      int readPageSize,
      int maxPages,
      int maxImageBytes,
      String syncChat,
      String cli) {
    public Config {
      if (botId == null || botId.isBlank() || appId == null || appId.isBlank())
        throw new IllegalArgumentException("Bot/app identity required");
      chats = chats == null ? List.of() : List.copyOf(chats);
      if (chats.stream().map(Chat::chatId).distinct().count() != chats.size())
        throw new IllegalArgumentException("Duplicate chat");
      if (otherChats == null) otherChats = "none";
      if (!Set.of("none", "mentions").contains(otherChats))
        throw new IllegalArgumentException("Invalid otherChats");
      if (pollSeconds == 0) pollSeconds = 60;
      if (readPageSize == 0) readPageSize = 20;
      if (maxPages == 0) maxPages = 100;
      if (maxImageBytes == 0) maxImageBytes = 10 * 1024 * 1024;
      if (pollSeconds < 1
          || pollSeconds > 3600
          || readPageSize < 1
          || readPageSize > 50
          || maxPages < 1
          || maxPages > 1000
          || maxImageBytes < 1
          || maxImageBytes > 20 * 1024 * 1024)
        throw new IllegalArgumentException("Invalid source limits");
      if (cli == null) cli = "";
      if (cli.contains("\n") || cli.contains("\r"))
        throw new IllegalArgumentException("Invalid CLI executable");
      if (syncChat == null) syncChat = "";
      if (!syncChat.isBlank()) FeishuProtocol.safe(syncChat, "oc_");
    }

    public Config(
        boolean enabled,
        boolean writesEnabled,
        String botId,
        String appId,
        List<Chat> chats,
        boolean directMessages,
        String otherChats,
        int pollSeconds,
        int readPageSize,
        int maxPages,
        int maxImageBytes,
        String syncChat) {
      this(
          enabled,
          writesEnabled,
          botId,
          appId,
          chats,
          directMessages,
          otherChats,
          pollSeconds,
          readPageSize,
          maxPages,
          maxImageBytes,
          syncChat,
          "");
    }

    public static Config defaults(String bot, String app) {
      return new Config(
          true, false, bot, app, List.of(), true, "none", 60, 20, 100, 10 * 1024 * 1024, "");
    }
  }

  public record Scope(boolean listened, boolean observed, String row) {}

  private final String id, token;
  private final URI base;
  private volatile Config config;
  private volatile FeishuEventConsumer consumer;
  private final java.util.concurrent.atomic.AtomicLong skipped =
      new java.util.concurrent.atomic.AtomicLong();

  public FeishuSource(String id, URI base, String token, Config config) {
    this.id = Objects.requireNonNull(id);
    this.base = base;
    this.token = token;
    this.config = config;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String kind() {
    return "feishu";
  }

  @Override
  public boolean enabled() {
    return config.enabled();
  }

  @Override
  public Config configuration() {
    return config;
  }

  @Override
  public void configure(JsonNode value) {
    config = HttpJsonTransport.JSON.convertValue(value, Config.class);
  }

  private FeishuProtocol api() {
    Config c = config;
    return new FeishuProtocol(base, token, id, c.botId(), c.appId(), c.maxPages(), c.cli());
  }

  private ObjectNode state(SourceContext ctx, String key) {
    JsonNode node = ctx.state(id, key, JsonNode.class);
    return node != null && node.isObject()
        ? ((ObjectNode) node).deepCopy()
        : HttpJsonTransport.JSON.createObjectNode();
  }

  public Scope scope(String chat, boolean direct) {
    Config c = config;
    if (!c.enabled()) return new Scope(false, false, "disabled");
    if (direct) return new Scope(c.directMessages(), false, "direct");
    for (Chat item : c.chats())
      if (item.chatId().equals(chat)) return new Scope(true, item.listen().equals("all"), chat);
    return new Scope(c.otherChats().equals("mentions"), false, "other");
  }

  /** 只允许已认证的 receiver 调用；接入不调用外部 API，因此可在3秒内提交事件。 */
  public boolean receiveVerified(SourceContext ctx, JsonNode event) throws Exception {
    if (!enabled()) return false;
    String eventApp = event.path("header").path("app_id").asText(config.appId());
    if (!config.appId().equals(eventApp))
      throw new SecurityException("Event belongs to another app");
    ReceivedEvent received = api().event(event);
    if (received == null) return false;
    return ctx.store().atomic(() -> ingest(ctx, received));
  }

  private boolean ingest(SourceContext ctx, ReceivedEvent event) {
    if (event == null) return false;
    String chat = Objects.toString(event.metadata().get("chatId"), "");
    boolean direct = Boolean.TRUE.equals(event.metadata().get("direct"));
    Scope scope = scope(chat, direct);
    if (!scope.listened()) return false;
    // 仅@模式仍保留已订阅线程上下文；新、未订阅线程的未@消息不进入领域。
    boolean subscribed = ctx.subscribed(id).stream().anyMatch(t -> t.key.equals(event.threadId()));
    if (!scope.observed() && !event.addressed() && !subscribed) return false;
    ctx.engine().receive(SourceBridge.toTrigger(event, scope.row(), scope.observed()));
    ctx.engine().describeThread(event.threadId(), event.metadata());
    ObjectNode chats = state(ctx, "known-chats");
    chats.put(chat, direct);
    ctx.state(id, "known-chats", chats);
    return true;
  }

  @Override
  public synchronized PollResult poll(SourceContext ctx) {
    installScope(ctx);
    skipped.set(0);
    if (!enabled()) return new PollResult(0, config.pollSeconds(), List.of());
    List<String> errors = new ArrayList<>();
    int received = 0;
    try {
      refreshChatNames(ctx);
    } catch (Exception e) {
      errors.add("chat-names: " + e.getClass().getSimpleName());
    }
    Map<String, Boolean> chats = new LinkedHashMap<>();
    for (Chat chat : config.chats()) chats.put(chat.chatId(), false);
    state(ctx, "known-chats")
        .fields()
        .forEachRemaining(
            e -> {
              if (e.getValue().asBoolean() && config.directMessages()) chats.put(e.getKey(), true);
            });
    for (var chat : chats.entrySet()) {
      if (!enabled()) break;
      String key = "history:" + chat.getKey();
      String since = ctx.state(id, key, String.class);
      Instant until = ctx.now();
      if (since == null) {
        ctx.state(id, key, until.toString());
        continue;
      }
      try {
        received += backfill(ctx, chat.getKey(), chat.getValue(), Instant.parse(since), until);
      } catch (Exception e) {
        errors.add("history " + chat.getKey() + ": " + e.getClass().getSimpleName());
      }
    }
    for (ThreadState thread : ctx.subscribed(id))
      if (enabled() && ctx.polled(thread))
        try {
          received += pollThread(ctx, thread);
          ctx.engine().describeThread(thread.key, Map.of("pollError", ""));
        } catch (Exception e) {
          errors.add("thread " + thread.key + ": " + e.getClass().getSimpleName());
          ctx.engine()
              .describeThread(thread.key, Map.of("pollError", e.getClass().getSimpleName()));
        }
    ctx.state(
        id,
        "health",
        Map.of(
            "state",
            errors.isEmpty() ? "ready" : "error",
            "errors",
            errors,
            "at",
            ctx.now().toString(),
            "received",
            received,
            "skipped",
            skipped.get()));
    return new PollResult(received, config.pollSeconds(), errors);
  }

  public int backfill(SourceContext ctx, String chat, boolean direct, Instant since, Instant until)
      throws Exception {
    FeishuProtocol api = api();
    ObjectNode topics = state(ctx, "topics:" + chat);
    List<ReceivedEvent> events = new ArrayList<>();
    scan(api, "chat", chat, chat, direct, since, until, null, events, topics);
    Set<String> followed = new HashSet<>();
    for (ThreadState thread : ctx.subscribed(id))
      if (ctx.polled(thread)) {
        Trigger trigger = anchor(ctx, thread.key);
        if (trigger != null && trigger.metadata().containsKey("threadId"))
          followed.add(trigger.metadata().get("threadId").toString());
      }
    List<String> ids = new ArrayList<>();
    topics.fieldNames().forEachRemaining(ids::add);
    for (String topic : ids) {
      if (!followed.contains(topic)
          && Instant.parse(topics.path(topic).asText())
              .isBefore(until.minus(Duration.ofDays(14)))) {
        topics.remove(topic);
        continue;
      }
      scan(api, "thread", topic, chat, direct, since, until, null, events, topics);
    }
    events.sort(Comparator.comparing(ReceivedEvent::at).thenComparing(ReceivedEvent::eventId));
    return ctx.store()
        .atomic(
            () -> {
              int count = 0;
              for (ReceivedEvent event : events) if (ingest(ctx, event)) count++;
              ctx.state(id, "topics:" + chat, topics);
              ctx.state(id, "history:" + chat, until.minusSeconds(5).toString());
              return count;
            });
  }

  private void scan(
      FeishuProtocol api,
      String kind,
      String container,
      String chat,
      boolean direct,
      Instant since,
      Instant until,
      String root,
      List<ReceivedEvent> events,
      ObjectNode topics)
      throws Exception {
    String cursor = null;
    Set<String> visited = new HashSet<>();
    for (int page = 0; page < config.maxPages(); page++) {
      if (!enabled()) throw new IllegalStateException("Source disabled during recovery");
      var result = api.history(kind, container, chat, since, until, cursor, 50, false);
      skipped.addAndGet(result.skipped());
      for (JsonNode message : result.items()) {
        if (message.path("deleted").asBoolean()) continue;
        ReceivedEvent event;
        try {
          event = api.historical(message, direct, root);
        } catch (IOException | IllegalArgumentException malformed) {
          skipped.incrementAndGet();
          continue;
        }
        if (event != null) events.add(event);
        String topic = message.path("thread_id").asText();
        if (!topic.isBlank()) {
          Instant at = FeishuProtocol.time(message.path("create_time").asText());
          if (!topics.has(topic) || at.isAfter(Instant.parse(topics.path(topic).asText())))
            topics.put(topic, at.toString());
        }
      }
      cursor = result.next();
      if (cursor == null) return;
      if (!visited.add(cursor)) throw new IOException("History pagination cycle");
    }
    throw new IOException("History page limit reached");
  }

  private int pollThread(SourceContext ctx, ThreadState thread) throws Exception {
    Trigger trigger = anchor(ctx, thread.key);
    if (trigger == null) throw new IOException("Subscribed thread lacks source anchor");
    String chat = Objects.toString(trigger.metadata().get("chatId"), ""),
        messageId = Objects.toString(trigger.metadata().get("messageId"), "");
    FeishuProtocol api = api();
    JsonNode source = api.message(messageId, chat);
    String topic = source.path("thread_id").asText();
    if (topic.isBlank()) return 0;
    String root = source.path("root_id").asText(messageId);
    if (root.isBlank()) root = messageId;
    ObjectNode prior = state(ctx, "thread:" + thread.key);
    Set<String> seen = new LinkedHashSet<>();
    prior.path("seen").forEach(n -> seen.add(n.asText()));
    boolean initialized = prior.path("initialized").asBoolean();
    if (!initialized) {
      seen.add(root);
      if (source.hasNonNull("parent_id")) seen.add(source.path("parent_id").asText());
    }
    List<ReceivedEvent> events = new ArrayList<>();
    String cursor = null;
    Set<String> pages = new HashSet<>();
    boolean complete = false;
    for (int page = 0; page < config.maxPages(); page++) {
      var batch =
          api.history("thread", topic, chat, null, null, cursor, config.readPageSize(), true);
      skipped.addAndGet(batch.skipped());
      boolean encountered =
          batch.items().stream().anyMatch(i -> seen.contains(i.path("message_id").asText()));
      List<JsonNode> chronological = new ArrayList<>(batch.items());
      Collections.reverse(chronological);
      for (JsonNode message : chronological) {
        String mid = message.path("message_id").asText();
        if (!seen.contains(mid) && !message.path("deleted").asBoolean()) {
          ReceivedEvent event;
          try {
            event =
                api.historical(
                    message, Boolean.TRUE.equals(trigger.metadata().get("direct")), root);
          } catch (IOException | IllegalArgumentException malformed) {
            skipped.incrementAndGet();
            seen.add(mid);
            continue;
          }
          if (event != null
              && (initialized || !event.at().isBefore(Instant.ofEpochMilli(thread.createdAt))))
            events.add(event);
        }
        seen.add(mid);
      }
      cursor = batch.next();
      if (cursor == null || (initialized && encountered)) {
        complete = true;
        break;
      }
      if (!pages.add(cursor)) throw new IOException("Thread pagination cycle");
    }
    if (!complete) throw new IOException("Thread page limit reached");
    ObjectNode next = HttpJsonTransport.JSON.createObjectNode().put("initialized", true);
    next.set("seen", HttpJsonTransport.JSON.valueToTree(seen));
    events.sort(Comparator.comparing(ReceivedEvent::at).thenComparing(ReceivedEvent::eventId));
    return ctx.store()
        .atomic(
            () -> {
              int count = 0;
              for (ReceivedEvent event : events) if (ingest(ctx, event)) count++;
              ctx.state(id, "thread:" + thread.key, next);
              ctx.engine().describeThread(thread.key, Map.of("threadId", topic));
              return count;
            });
  }

  private Trigger anchor(SourceContext ctx, String thread) {
    return ctx.store().list("trigger", Trigger.class).stream()
        .filter(
            t ->
                t.source().equals(id)
                    && t.threads().stream().anyMatch(r -> r.key().equals(thread))
                    && t.metadata().containsKey("messageId"))
        .findFirst()
        .orElse(null);
  }

  @Override
  public Find find(String query) {
    Set<String> terms = new LinkedHashSet<>();
    var matches = java.util.regex.Pattern.compile("om_[A-Za-z0-9_-]+").matcher(query);
    while (matches.find()) terms.add(matches.group());
    return new Find(List.of(), List.copyOf(terms));
  }

  public JsonNode checkIdentity() throws Exception {
    return api().identity();
  }

  public Map<String, String> refreshChatNames(SourceContext ctx) throws Exception {
    Map<String, String> names = new LinkedHashMap<>();
    for (JsonNode chat : api().all("im/v1/chats", 100)) {
      String chatId = chat.path("chat_id").asText();
      if (!chatId.isBlank()) names.put(chatId, chat.path("name").asText(chatId));
    }
    ctx.state(id, "labels", names);
    return names;
  }

  /** 完整读取而不偷偷修改 Policy；调用者通过 VersionedSettings 乐观锁提交并保存历史。 */
  public Map<String, String> readMembers() throws Exception {
    if (config.syncChat().isBlank()) throw new IllegalStateException("No sync chat configured");
    Map<String, String> members = new LinkedHashMap<>();
    for (JsonNode item :
        api().all("im/v1/chats/" + config.syncChat() + "/members?member_id_type=open_id", 100)) {
      String member = item.path("member_id").asText();
      if (!member.startsWith("ou_")) throw new IOException("Expected open_id user member");
      members.put(member, item.path("name").asText());
    }
    return Collections.unmodifiableMap(members);
  }

  /** 已明确配置的 lark-cli 事件流是可信 Source 传输，不接受来自网络任意调用此方法。 */
  public boolean receiveCli(SourceContext ctx, JsonNode event) throws Exception {
    if (!enabled()) return false;
    ReceivedEvent received = api().normalizedEvent(event);
    return ctx.store().atomic(() -> ingest(ctx, received));
  }

  @Override
  public Object read(SourceContext ctx, String resource) throws Exception {
    if (!enabled()) throw new IllegalStateException("Source disabled");
    Matcher match =
        Pattern.compile(
                "feishu/([^/]+)/(message|root|parent|thread|around|image|link)(?:/([^/]+))?")
            .matcher(resource);
    if (!match.matches()) throw new IllegalArgumentException("Invalid Feishu resource");
    String triggerId = URLDecoder.decode(match.group(1), StandardCharsets.UTF_8),
        section = match.group(2),
        cursor =
            match.group(3) == null
                ? null
                : URLDecoder.decode(match.group(3), StandardCharsets.UTF_8);
    if (cursor != null && !Set.of("thread", "around", "image").contains(section))
      throw new IllegalArgumentException("Resource does not accept cursor");
    Trigger trigger = ctx.engine().trigger(triggerId);
    if (trigger == null
        || !trigger.source().equals(id)
        || !trigger.metadata().containsKey("messageId"))
      throw new IllegalArgumentException("Source trigger anchor required");
    String chat = trigger.metadata().get("chatId").toString(),
        messageId = trigger.metadata().get("messageId").toString();
    FeishuProtocol api = api();
    JsonNode source = api.message(messageId, chat);
    if (source.path("deleted").asBoolean())
      throw new IOException("Source message deleted; retained trigger remains readable");
    if (section.equals("link")) return api.link(messageId, chat);
    if (section.equals("image")) {
      if (cursor == null) throw new IllegalArgumentException("Image key required");
      return api.image(messageId, chat, cursor, config.maxImageBytes());
    }
    if (Set.of("message", "root", "parent").contains(section)) {
      String target = section.equals("message") ? messageId : source.path(section + "_id").asText();
      Map<String, Object> out = new LinkedHashMap<>();
      if (target.isBlank()) {
        out.put("status", "no_" + section);
        out.put("message", null);
        return out;
      }
      JsonNode value = target.equals(messageId) ? source : api.message(target, chat);
      out.put("status", value.path("deleted").asBoolean() ? "deleted" : "available");
      out.put("message", api.view(value));
      return out;
    }
    if (section.equals("thread")) {
      String thread = source.path("thread_id").asText();
      if (thread.isBlank()) {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("status", "no_thread");
        empty.put("messages", List.of());
        empty.put("next_resource", null);
        return empty;
      }
      var page =
          api.history("thread", thread, chat, null, null, cursor, config.readPageSize(), false);
      List<Map<String, Object>> messages = new ArrayList<>();
      for (JsonNode item : page.items()) messages.add(api.view(item));
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("status", "available");
      result.put("thread_id", thread);
      result.put("messages", messages);
      result.put(
          "next_resource",
          page.next() == null
              ? null
              : "feishu/" + triggerId + "/thread/" + GitHubProtocol.encode(page.next()));
      if (page.skipped() > 0) result.put("skipped", page.skipped());
      return result;
    }
    Instant at = Instant.parse(trigger.at());
    var earlier = api.history("chat", chat, chat, null, at, cursor, cursor == null ? 6 : 10, true);
    List<JsonNode> combined = new ArrayList<>(earlier.items());
    Collections.reverse(combined);
    if (cursor == null)
      combined.addAll(api.history("chat", chat, chat, at, null, null, 6, false).items());
    Set<String> seen = new HashSet<>();
    List<Map<String, Object>> messages = new ArrayList<>();
    for (JsonNode item : combined)
      if (seen.add(item.path("message_id").asText())) messages.add(api.view(item));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "available");
    result.put("messages", messages);
    result.put(
        "next_resource",
        earlier.next() == null
            ? null
            : "feishu/" + triggerId + "/around/" + GitHubProtocol.encode(earlier.next()));
    return result;
  }

  @Override
  public Object readContext(SourceContext ctx, Trigger trigger, String part, String cursor)
      throws Exception {
    if (!trigger.source().equals(id)) throw new IllegalArgumentException("Wrong source");
    Object body =
        read(
            ctx,
            "feishu/"
                + GitHubProtocol.encode(trigger.id())
                + "/"
                + part
                + (cursor == null ? "" : "/" + GitHubProtocol.encode(cursor)));
    if (part.equals("thread")
        && body instanceof Map<?, ?> result
        && "no_thread".equals(result.get("status"))) {
      String chat = Objects.toString(trigger.metadata().get("chatId"));
      var api = api();
      var page = api.history("chat", chat, chat, null, Instant.parse(trigger.at()), null, 6, true);
      List<JsonNode> ordered = new ArrayList<>(page.items());
      Collections.reverse(ordered);
      List<Map<String, Object>> messages = new ArrayList<>();
      for (JsonNode item : ordered) messages.add(api.view(item));
      return Map.of("status", "chat_neighbours", "messages", messages);
    }
    return body;
  }

  @Override
  public Object readContextImage(
      SourceContext ctx, Trigger trigger, String messageId, String imageKey) throws Exception {
    if (!enabled() || !trigger.source().equals(id) || !trigger.metadata().containsKey("chatId"))
      throw new IllegalArgumentException("Source trigger required");
    String chat = trigger.metadata().get("chatId").toString();
    return api().image(messageId, chat, imageKey, config.maxImageBytes());
  }

  @Override
  public Delivery send(SourceContext ctx, OutboxMessage message) throws Exception {
    if (!enabled()) return new Delivery("waiting", null);
    Map<String, String> address = message.channel().address();
    Scope allowed =
        scope(
            address.getOrDefault("chatId", ""),
            Boolean.parseBoolean(address.getOrDefault("direct", "false")));
    if (!allowed.listened()) return new Delivery("gone", null);
    if (!config.writesEnabled()) return new Delivery("waiting", null);
    try {
      return new Delivery(
          "sent", api().reply(address.get("messageId"), message.text(), message.id()));
    } catch (FeishuProtocol.ApiException e) {
      if (e.gone()) return new Delivery("gone", null);
      throw e;
    }
  }

  @Override
  public void installScope(SourceContext ctx) {
    Set<String> scopes = new LinkedHashSet<>();
    config.chats().forEach(c -> scopes.add(c.chatId()));
    if (config.directMessages()) scopes.add("direct");
    if (config.otherChats().equals("mentions")) scopes.add("other");
    if (scopes.isEmpty()) scopes.add("__none__");
    ctx.engine().configureSourceScope(id, new SourceScope(enabled(), scopes, config.botId()));
  }

  @Override
  public synchronized void start(SourceContext context) throws Exception {
    installScope(context);
    if (consumer == null && !config.cli().isBlank()) {
      consumer = new FeishuEventConsumer(this, context, config.cli());
      consumer.start();
    }
  }

  @Override
  public synchronized void close() {
    if (consumer != null) {
      consumer.close();
      consumer = null;
    }
  }
}
