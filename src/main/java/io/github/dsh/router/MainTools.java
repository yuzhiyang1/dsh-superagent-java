package io.github.dsh.router;

import static io.github.dsh.config.Json.*;

import com.fasterxml.jackson.databind.*;
import io.github.dsh.config.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import io.github.dsh.management.*;
import java.util.*;

/** 主会话的14个领域工具。模型只选择动作；权限、字段、版本和逐项失败由 Java 宿主执行。 */
public final class MainTools {
  private final Engine engine;
  private final SqliteStore store;
  private final VersionedSettings settings;
  private final PromptService prompts;
  private final ResourceService resources;
  private final ManagementHost host;
  private final CandidateContext candidates;

  public MainTools(
      Engine engine,
      SqliteStore store,
      VersionedSettings settings,
      PromptService prompts,
      ResourceService resources,
      ManagementHost host) {
    this.engine = engine;
    this.store = store;
    this.settings = settings;
    this.prompts = prompts;
    this.resources = resources;
    this.host = host;
    candidates = new CandidateContext(engine, store);
  }

  // 缓存绑定 Eval 而非全局消息 ID；模型只能展开本次读取确实到达的引用。
  private static final class ReadCache {
    final Map<String, String> messages = new LinkedHashMap<>(),
        images = new LinkedHashMap<>(),
        snapshots = new HashMap<>();
    final Set<String> cursors = new HashSet<>();
    final List<JsonNode> initial = new ArrayList<>();
    boolean seeded;
    String error;
  }

  private final Map<String, ReadCache> reads = new LinkedHashMap<>();

  private ReadCache cache(Evaluation evaluation) {
    if (reads.size() > 64) reads.keySet().removeIf(id -> engine.evaluationById(id) == null);
    return reads.computeIfAbsent(evaluation.id(), id -> new ReadCache());
  }

  private static String sourceMessage(Trigger trigger) {
    return Objects.toString(
        trigger.metadata().get("messageId"), trigger.url() == null ? trigger.id() : trigger.url());
  }

  private void register(ReadCache cache, JsonNode context) {
    for (JsonNode item : context.path("messages")) {
      if (item.path("deleted").asBoolean()) continue;
      String id = item.path("id").asText(),
          text = item.path("text").asText(item.path("body").asText());
      if (id.isBlank()) continue;
      cache.messages.put(id, text);
      for (String key : io.github.dsh.sources.FeishuProtocol.imageKeys(text))
        cache.images.put(id + "/" + key, id);
    }
    String next = context.path("next").asText(context.path("next_cursor").asText());
    if (!next.isBlank()) cache.cursors.add(next);
  }

  private JsonNode boundedRead(String resource, long remaining) throws Exception {
    if (remaining <= 0) throw new java.util.concurrent.TimeoutException("Context deadline elapsed");
    var task = new java.util.concurrent.FutureTask<JsonNode>(() -> host.readSource(resource));
    Thread.ofVirtual().start(task);
    try {
      return task.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
    } finally {
      if (!task.isDone()) task.cancel(true);
    }
  }

  private ReadCache seed(Evaluation evaluation) {
    ReadCache cache = cache(evaluation);
    if (cache.seeded) return cache;
    cache.seeded = true;
    Trigger trigger = engine.trigger(evaluation.primaryTriggerId());
    cache.messages.put(sourceMessage(trigger), trigger.text());
    for (String key : io.github.dsh.sources.FeishuProtocol.imageKeys(trigger.text()))
      cache.images.put(sourceMessage(trigger) + "/" + key, sourceMessage(trigger));
    try {
      long deadline =
          System.nanoTime()
              + settings.value("deployment").path("admissionContextTimeoutMs").asLong(15000)
                  * 1_000_000L;
      int max = settings.value("deployment").path("admissionContextMaxPages").asInt(10);
      String cursor = null;
      Set<String> seen = new HashSet<>();
      for (int page = 0; page < max; page++) {
        JsonNode context =
            boundedRead(
                trigger.source()
                    + "/context/"
                    + trigger.id()
                    + "/thread"
                    + (cursor == null ? "" : "?cursor=" + encode(cursor)),
                deadline - System.nanoTime());
        register(cache, context);
        for (JsonNode item : context.path("messages"))
          if (seen.add(item.path("id").asText())) cache.initial.add(item);
        String next = context.path("next").asText(context.path("next_cursor").asText());
        if (next.isBlank() || next.equals(cursor)) break;
        cursor = next;
      }
    } catch (Exception failure) {
      cache.error = failure.getClass().getSimpleName();
    }
    return cache;
  }

  public Map<String, Object> candidateCard(Evaluation evaluation) {
    Map<String, Object> card = new LinkedHashMap<>(candidates.card(evaluation));
    Trigger trigger = engine.trigger(evaluation.primaryTriggerId());
    ReadCache cache = seed(evaluation);
    List<JsonNode> messages = cache.initial;
    int index = -1;
    for (int i = 0; i < messages.size(); i++)
      if (messages.get(i).path("id").asText().equals(sourceMessage(trigger))) index = i;
    List<Object> window = new ArrayList<>(), stubs = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      JsonNode item = messages.get(i);
      boolean visible = i < 5 || index >= 0 && Math.abs(i - index) <= 5;
      int cap =
          i == index ? 4000 : index >= 0 && Math.abs(i - index) <= 2 ? 1200 : visible ? 400 : 50;
      String text = item.path("text").asText(item.path("body").asText()),
          id = item.path("id").asText();
      Object row =
          object(
              "id",
              id,
              "from",
              item.path("from"),
              visible ? "text" : "preview",
              text.substring(0, Math.min(cap, text.length())),
              "chars",
              text.length(),
              "images",
              cache.images.keySet().stream().filter(k -> cache.images.get(k).equals(id)).toList());
      (visible ? window : stubs).add(row);
    }
    card.put("source_window", window);
    card.put("source_stubs", stubs);
    if (cache.error != null) card.put("context_error", cache.error);
    return card;
  }

  public Object invoke(String name, JsonNode args) throws Exception {
    return engine.withActor("main", () -> invokeCommand(name, args));
  }

  private Object invokeCommand(String name, JsonNode args) throws Exception {
    Object result =
        switch (name) {
          case "orchestrator_read" ->
              resources.read(
                  required(args, "resource"),
                  args.path("offset").asInt(),
                  args.path("limit")
                      .asInt(ResourceService.defaultLimit(required(args, "resource"))),
                  null);
          case "orchestrator_route" -> route(args.path("decisions"));
          case "orchestrator_ticket_retry" -> engine.retryTicket(required(args, "ticket_id"));
          case "orchestrator_ticket_request_status" ->
              Map.of("requested", engine.requestStatus(required(args, "ticket_id")));
          case "orchestrator_ticket_note" -> {
            String text = required(args, "text");
            AssessmentValidator.words(text, 100);
            yield engine.note(required(args, "ticket_id"), text);
          }
          case "orchestrator_trigger_requeue" ->
              Map.of("queued", engine.reevaluate(required(args, "trigger_id")));
          case "orchestrator_eval" -> {
            Evaluation evaluation = candidates.evaluation(required(args, "trigger_id"));
            if (evaluation.strength() != Strength.INFERRED)
              throw new IllegalArgumentException("Explicit candidates use orchestrator_route");
            yield candidateCard(evaluation);
          }
          case "orchestrator_eval_read" -> evalRead(args);
          case "orchestrator_eval_decide" -> assess(args);
          case "orchestrator_reply" -> {
            String text = required(args, "text");
            AssessmentValidator.words(text, 100);
            String purpose = required(args, "purpose");
            if (!Set.of("answer", "result", "attention").contains(purpose))
              throw new IllegalArgumentException(
                  "Main reply purpose must be answer, result or attention");
            yield engine.reply(
                new ReplyRequest(
                    ReplyPurpose.valueOf(purpose.toUpperCase()),
                    optional(args, "ticket_id"),
                    optional(args, "trigger_id"),
                    args.hasNonNull("human_action")
                        ? optional(args, "human_action")
                        : optional(args, "question"),
                    text));
          }
          case "orchestrator_thread_unsubscribe" ->
              Map.of("removed", engine.unsubscribe(thread(required(args, "thread"))));
          case "orchestrator_settings_save" -> {
            String block = required(args, "block");
            if (block.equals("source")) block = "source:" + required(args, "source");
            yield settings.save(block, args.path("value"), args.path("revision").asInt(-1), "tool");
          }
          case "orchestrator_policy_sync" -> synchronizePolicy(required(args, "source"));
          case "orchestrator_prompts_save" ->
              args.path("preview").asBoolean()
                  ? preview(args.path("config"))
                  : prompts.save(args.path("config"), args.path("baseRevision").asInt(-1), "tool");
          default -> throw new IllegalArgumentException("Unknown main tool: " + name);
        };
    host.requestPass();
    return result;
  }

  private Object preview(JsonNode config) {
    JsonNode composed = prompts.preview(config);
    List<Object> previews = new ArrayList<>();
    for (String role : List.of("orchestrator", "worker")) {
      String text = composed.path(role).asText();
      previews.add(
          object(
              "role", role, "chars", text.length(), "read", resources.savePreviewText(role, text)));
    }
    return object("scope", composed.path("scope"), "previews", previews);
  }

  private Object synchronizePolicy(String source) {
    int revision = settings.revision("policy");
    JsonNode before = settings.value("policy").path("sources").path(source).path("allowed");
    JsonNode part = host.synchronizePolicy(source);
    com.fasterxml.jackson.databind.node.ObjectNode policy = settings.value("policy").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) policy.path("sources")).set(source, part);
    settings.save("policy", policy, revision, "sync");
    JsonNode after = part.path("allowed");
    return object(
        "source",
        source,
        "allowed",
        after.size(),
        "added",
        changedNames(after, before),
        "removed",
        changedNames(before, after));
  }

  private static List<String> changedNames(JsonNode from, JsonNode without) {
    Set<String> ids = new HashSet<>();
    without.forEach(item -> ids.add(item.path("id").asText()));
    List<String> names = new ArrayList<>();
    from.forEach(
        item -> {
          if (!ids.contains(item.path("id").asText()))
            names.add(item.path("name").asText(item.path("id").asText()));
        });
    return names;
  }

  private Object route(JsonNode decisions) {
    if (!decisions.isArray() || decisions.isEmpty() || decisions.size() > 20)
      throw new IllegalArgumentException("Route 1..20 decisions");
    List<Object> results = new ArrayList<>();
    Set<String> decided = new HashSet<>();
    for (JsonNode item : decisions) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("trigger_id", item.path("trigger_id").asText());
      row.put("kind", item.path("kind").asText());
      try {
        String triggerId = required(item, "trigger_id"), kind = required(item, "kind");
        if (kind.equals("ignore") && decided.contains(triggerId))
          throw new IllegalArgumentException("Trigger already decided in this batch");
        switch (kind) {
          case "accept" -> {
            String title = AssessmentValidator.required(item, "title", 200),
                shortName = AssessmentValidator.worktree(required(item, "worktree_name")),
                ack = required(item, "acknowledgement");
            AssessmentValidator.words(ack, 100);
            String note = optional(item, "note");
            if (note != null) AssessmentValidator.words(note, 30);
            Decision accepted =
                engine.decisions().stream()
                    .filter(
                        d -> d.kind() == DecisionKind.ACCEPT && d.triggerIds().contains(triggerId))
                    .findFirst()
                    .orElse(null);
            if (accepted != null) {
              row.put("decision", accepted);
              row.put("ticket", engine.ticket(accepted.ticketId()));
              break;
            }
            if (engine.admissionStrength(triggerId) != Strength.EXPLICIT)
              throw new IllegalArgumentException(
                  "Inferred candidates require orchestrator_eval_decide");
            Decision decision =
                engine.decideTrigger(
                    triggerId,
                    new Outcome(
                        DecisionKind.ACCEPT,
                        title,
                        title,
                        Set.of(),
                        "explicit",
                        null,
                        shortName,
                        ack,
                        note,
                        MAPPER.convertValue(item, Map.class)));
            row.put("decision", decision);
            if (decision.ticketId() != null) row.put("ticket", engine.ticket(decision.ticketId()));
          }
          case "attach" -> {
            String ticketId = required(item, "ticket_id");
            Ticket ticket = engine.ticket(ticketId);
            Trigger trigger = engine.trigger(triggerId);
            if (ticket == null || trigger == null || !explicitReference(ticket, trigger))
              throw new IllegalArgumentException(
                  "Attachment requires an explicit ticket/issue/PR/source reference");
            String note = optional(item, "note");
            if (note != null) AssessmentValidator.words(note, 30);
            row.put("ticket", engine.attach(ticketId, triggerId, note));
          }
          case "ignore" -> {
            row.put(
                "decision",
                engine.decideTrigger(
                    triggerId, Outcome.ignore(AssessmentValidator.required(item, "reason", 500))));
          }
          case "stop" -> {
            String ticketId = required(item, "ticket_id");
            row.put(
                "decision",
                engine.decideTrigger(
                    triggerId,
                    new Outcome(
                        DecisionKind.STOP,
                        null,
                        null,
                        Set.of(),
                        item.path("reason").asText("humanStop"),
                        ticketId)));
            row.put("ticket", engine.ticket(ticketId));
          }
          default -> throw new IllegalArgumentException("Unknown routing kind");
        }
        decided.add(triggerId);
      } catch (RuntimeException failure) {
        row.put(
            "refused", Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
      }
      results.add(row);
    }
    return Map.of("results", results);
  }

  private Decision assess(JsonNode args) {
    String triggerId = required(args, "trigger_id");
    Evaluation evaluation = candidates.evaluation(triggerId);
    if (evaluation.strength() != Strength.INFERRED)
      throw new IllegalArgumentException("Explicit candidates use route");
    JsonNode assessment = args.has("assessment") ? args.path("assessment") : args.deepCopy();
    if (!args.has("assessment"))
      ((com.fasterxml.jackson.databind.node.ObjectNode) assessment).remove("trigger_id");
    Outcome outcome = AssessmentValidator.validate(assessment, engine.policy().enabledRules());
    com.fasterxml.jackson.databind.node.ObjectNode audited = assessment.deepCopy();
    List<JsonNode> matches = new ArrayList<>();
    for (JsonNode rule : assessment.path("rule_assessments"))
      if (rule.path("matched").asBoolean())
        matches.add(object("rule_id", rule.path("rule_id"), "reason", rule.path("reason")));
    audited.set("matches", MAPPER.valueToTree(matches));
    assessment = audited;
    if (outcome.kind() == DecisionKind.ACCEPT && engine.policy().acceptMode() != AcceptMode.AUTO) {
      com.fasterxml.jackson.databind.node.ObjectNode observed = assessment.deepCopy();
      observed.put("would_accept", true);
      observed.set(
          "proposal",
          object(
              "title",
              outcome.title(),
              "scope",
              outcome.scope(),
              "worktree_name",
              assessment.path("worktree_name")));
      return decide(evaluation, Outcome.ignore("observed"), null, null, null, observed);
    }
    return decide(
        evaluation,
        outcome,
        optional(assessment, "worktree_name"),
        null,
        optional(assessment, "acknowledgement"),
        assessment);
  }

  private Decision decide(
      Evaluation evaluation,
      Outcome outcome,
      String name,
      String note,
      String acknowledgement,
      JsonNode audit) {
    if (acknowledgement != null) AssessmentValidator.words(acknowledgement, 100);
    @SuppressWarnings("unchecked")
    Map<String, Object> evidence = MAPPER.convertValue(audit, Map.class);
    Outcome enriched =
        new Outcome(
            outcome.kind(),
            outcome.title(),
            outcome.scope(),
            outcome.matchedRules(),
            outcome.reason(),
            outcome.ticketId(),
            name,
            acknowledgement,
            note,
            evidence);
    return engine.decide(evaluation.id(), enriched);
  }

  private Object evalRead(JsonNode args) throws Exception {
    AssessmentValidator.fields(
        args,
        Set.of("trigger_id", "resource", "path", "message_id", "cursor", "image_id", "offset"));
    String triggerId = required(args, "trigger_id"), kind = required(args, "resource");
    Evaluation evaluation = candidates.evaluation(triggerId);
    if (evaluation.strength() != Strength.INFERRED)
      throw new IllegalArgumentException("Explicit candidate is not an assessment");
    if (!Set.of("message", "root", "parent", "thread", "file", "directory", "image").contains(kind))
      throw new IllegalArgumentException("Unknown context resource");
    boolean pathKind = Set.of("file", "directory").contains(kind);
    if (pathKind != args.hasNonNull("path")
        || args.hasNonNull("message_id") && !kind.equals("message")
        || args.hasNonNull("cursor") && !kind.equals("thread")
        || kind.equals("image") != args.hasNonNull("image_id"))
      throw new IllegalArgumentException("Arguments do not belong to context resource");
    if (args.has("offset")
        && (!args.path("offset").canConvertToInt() || !args.path("offset").isIntegralNumber()))
      throw new IllegalArgumentException("Offset must be integer");
    int offset = args.path("offset").asInt();
    if (offset < 0) throw new IllegalArgumentException("Invalid offset");
    ReadCache cache = seed(evaluation);
    Trigger trigger = engine.trigger(triggerId);
    String resource = trigger.source() + "/context/" + triggerId + "/" + kind;
    if (kind.equals("image")) {
      if (offset != 0) throw new IllegalArgumentException("Images cannot be paginated");
      String image = required(args, "image_id"), message = cache.images.get(image);
      if (message == null)
        throw new IllegalArgumentException("Unknown image reference; read source context first");
      return host.readSource(
          resource
              + "?message_id="
              + encode(message)
              + "&image_id="
              + encode(image.substring(message.length() + 1)));
    }
    String cursor = optional(args, "cursor");
    if (cursor != null && !cache.cursors.contains(cursor))
      throw new IllegalArgumentException("Unknown thread cursor");
    String
        key =
            kind + "|" + optional(args, "path") + "|" + cursor + "|" + optional(args, "message_id"),
        body;
    if (offset > 0) {
      body = cache.snapshots.get(key);
      if (body == null) throw new IllegalArgumentException("Read offset 0 first");
    } else if (kind.equals("message")) {
      String message = optional(args, "message_id");
      if (message == null) message = sourceMessage(trigger);
      body = cache.messages.get(message);
      if (body == null) {
        candidates.readMessage(triggerId, message, 0);
        Trigger original = engine.trigger(message);
        body = original.text();
      }
      cache.snapshots.put(key, body);
    } else {
      List<String> query = new ArrayList<>();
      if (pathKind) query.add("path=" + encode(required(args, "path")));
      if (cursor != null) query.add("cursor=" + encode(cursor));
      JsonNode result =
          host.readSource(resource + (query.isEmpty() ? "" : "?" + String.join("&", query)));
      register(cache, result);
      body = result.isTextual() ? result.asText() : result.toString();
      cache.snapshots.put(key, body);
    }
    if (offset > body.length()) throw new IllegalArgumentException("Invalid offset");
    int end = Math.min(body.length(), offset + 4000);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("resource", kind);
    result.put("offset", offset);
    result.put("text", body.substring(offset, end));
    result.put("total_chars", body.length());
    result.put("next_offset", end < body.length() ? end : null);
    return result;
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private boolean explicitReference(Ticket ticket, Trigger trigger) {
    String text = trigger.text();
    if (text.contains(ticket.id) || text.contains(ticket.originTriggerId)) return true;
    for (Channel channel : ticket.channels) {
      String number = channel.address().get("issue"),
          repository = channel.address().get("repository"),
          message = channel.address().get("messageId");
      if (number != null
          && (text.matches("(?s).*#" + java.util.regex.Pattern.quote(number) + "(?![0-9]).*")
              || repository != null && text.contains(repository + "/pull/" + number)
              || repository != null && text.contains(repository + "/issues/" + number)))
        return true;
      if (message != null && text.contains(message)) return true;
    }
    return false;
  }

  public static ThreadRef thread(String key) {
    int i = key.indexOf(':');
    if (i < 1 || i == key.length() - 1)
      throw new IllegalArgumentException("Expected source:thread");
    return new ThreadRef(key.substring(0, i), key.substring(i + 1));
  }

  private static String required(JsonNode args, String name) {
    return AssessmentValidator.required(args, name, 16000);
  }

  private static String optional(JsonNode args, String name) {
    return args.path(name).isTextual() && !args.path(name).asText().isBlank()
        ? args.path(name).asText()
        : null;
  }

  public static List<Map<String, Object>> schemas() {
    Map<String, String> descriptions = new LinkedHashMap<>();
    descriptions.put(
        "orchestrator_read", "Read paginated resource. Fields resource, offset=0, limit=20.");
    descriptions.put(
        "orchestrator_route",
        "Route 1..20 decisions in order. Each: kind accept|attach|ignore|stop, trigger_id; accept"
            + " needs title, worktree_name (1-4 English kebab words), acknowledgement <=100 words;"
            + " attach/stop ticket_id; ignore reason. Optional note <=30 words. Only explicit"
            + " accept here.");
    descriptions.put(
        "orchestrator_ticket_retry", "Retry failed delivery/blocked/failed ticket. ticket_id.");
    descriptions.put(
        "orchestrator_ticket_request_status", "Wake waiting ticket for status. ticket_id.");
    descriptions.put(
        "orchestrator_ticket_note", "Send context, not authority. ticket_id,text<=100 words.");
    descriptions.put(
        "orchestrator_trigger_requeue", "Requeue trigger_id, refuses owned human followup.");
    descriptions.put("orchestrator_eval", "Read inferred candidate facts. trigger_id.");
    descriptions.put(
        "orchestrator_eval_read",
        "Expand inferred candidate. trigger_id, resource"
            + " message|root|parent|thread|file|directory|image, optional"
            + " message_id/path/cursor/image_id/offset.");
    descriptions.put(
        "orchestrator_eval_decide",
        "trigger_id plus decision accept|ignore|unavailable,"
            + " title,scope,reason,worktree_name,acknowledgement,intent_evidence,rule_assessments:[{rule_id,matched,reason}]"
            + " exactly every enabled rule,"
            + " direction:{implementation_requested,target_basis:specified|existing-standard|undetermined,requires_design_decision,evidence}.");
    descriptions.put(
        "orchestrator_reply",
        "optional ticket_id,purpose answer|result|attention,text<=100 words; trigger_id for reply"
            + " to human; human_action for attention.");
    descriptions.put(
        "orchestrator_thread_unsubscribe", "Unsubscribe all tickets from thread source:thread.");
    descriptions.put(
        "orchestrator_settings_save",
        "block policy|eval|executor|pause|source; source if needed; value complete block, revision."
            + " Cannot change execution/account/deployment settings.");
    descriptions.put("orchestrator_policy_sync", "Sync configured source membership. source.");
    descriptions.put(
        "orchestrator_prompts_save", "config,baseRevision; preview=true composes without saving.");
    return descriptions.entrySet().stream()
        .map(
            e ->
                Map.<String, Object>of(
                    "type",
                    "function",
                    "function",
                    Map.of(
                        "name",
                        e.getKey(),
                        "description",
                        e.getValue(),
                        "parameters",
                        schema(e.getKey()))))
        .toList();
  }

  private static Map<String, Object> schema(String name) {
    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    switch (name) {
      case "orchestrator_read" -> {
        property(properties, "resource", "string");
        property(properties, "offset", "integer");
        property(properties, "limit", "integer");
        required.add("resource");
      }
      case "orchestrator_route" -> {
        Map<String, Object> item = new LinkedHashMap<>();
        for (String key :
            List.of(
                "kind",
                "trigger_id",
                "ticket_id",
                "title",
                "worktree_name",
                "acknowledgement",
                "note",
                "reason")) property(item, key, "string");
        item.put(
            "kind",
            Map.of("type", "string", "enum", List.of("accept", "attach", "ignore", "stop")));
        properties.put(
            "decisions",
            Map.of(
                "type",
                "array",
                "minItems",
                1,
                "maxItems",
                20,
                "items",
                objectSchema(item, List.of("kind", "trigger_id"))));
        required.add("decisions");
      }
      case "orchestrator_ticket_retry", "orchestrator_ticket_request_status" -> {
        property(properties, "ticket_id", "string");
        required.add("ticket_id");
      }
      case "orchestrator_ticket_note" -> {
        property(properties, "ticket_id", "string");
        property(properties, "text", "string");
        required.addAll(List.of("ticket_id", "text"));
      }
      case "orchestrator_trigger_requeue", "orchestrator_eval" -> {
        property(properties, "trigger_id", "string");
        required.add("trigger_id");
      }
      case "orchestrator_eval_read" -> {
        for (String key :
            List.of("trigger_id", "resource", "path", "message_id", "cursor", "image_id"))
          property(properties, key, "string");
        property(properties, "offset", "integer");
        properties.put(
            "resource",
            Map.of(
                "type",
                "string",
                "enum",
                List.of("message", "root", "parent", "thread", "file", "directory", "image")));
        required.addAll(List.of("trigger_id", "resource"));
      }
      case "orchestrator_eval_decide" -> {
        for (String key :
            List.of(
                "trigger_id",
                "decision",
                "title",
                "scope",
                "reason",
                "worktree_name",
                "intent_evidence",
                "acknowledgement")) property(properties, key, "string");
        properties.put(
            "decision",
            Map.of("type", "string", "enum", List.of("accept", "ignore", "unavailable")));
        Map<String, Object> rule = new LinkedHashMap<>();
        property(rule, "rule_id", "string");
        property(rule, "reason", "string");
        property(rule, "matched", "boolean");
        properties.put(
            "rule_assessments",
            Map.of(
                "type",
                "array",
                "items",
                objectSchema(rule, List.of("rule_id", "matched", "reason"))));
        Map<String, Object> direction = new LinkedHashMap<>();
        property(direction, "implementation_requested", "boolean");
        property(direction, "requires_design_decision", "boolean");
        property(direction, "evidence", "string");
        direction.put(
            "target_basis",
            Map.of(
                "type",
                "string",
                "enum",
                List.of("specified", "existing-standard", "undetermined")));
        properties.put("direction", objectSchema(direction, new ArrayList<>(direction.keySet())));
        required.addAll(
            List.of(
                "trigger_id",
                "decision",
                "title",
                "scope",
                "reason",
                "rule_assessments",
                "direction"));
      }
      case "orchestrator_reply" -> {
        for (String key : List.of("ticket_id", "trigger_id", "text", "human_action"))
          property(properties, key, "string");
        properties.put(
            "purpose", Map.of("type", "string", "enum", List.of("answer", "result", "attention")));
        required.addAll(List.of("purpose", "text"));
      }
      case "orchestrator_thread_unsubscribe" -> {
        property(properties, "thread", "string");
        required.add("thread");
      }
      case "orchestrator_settings_save" -> {
        property(properties, "block", "string");
        property(properties, "source", "string");
        property(properties, "revision", "integer");
        properties.put("value", Map.of("type", "object", "additionalProperties", true));
        required.addAll(List.of("block", "value", "revision"));
      }
      case "orchestrator_policy_sync" -> {
        property(properties, "source", "string");
        required.add("source");
      }
      case "orchestrator_prompts_save" -> {
        properties.put("config", Map.of("type", "object", "additionalProperties", true));
        property(properties, "baseRevision", "integer");
        property(properties, "preview", "boolean");
        required.add("config");
      }
      default -> throw new IllegalArgumentException("Unknown tool schema");
    }
    return objectSchema(properties, required);
  }

  private static void property(Map<String, Object> properties, String name, String type) {
    properties.put(name, Map.of("type", type));
  }

  private static Map<String, Object> objectSchema(
      Map<String, Object> properties, List<String> required) {
    return Map.of(
        "type",
        "object",
        "properties",
        properties,
        "required",
        required,
        "additionalProperties",
        false);
  }
}
