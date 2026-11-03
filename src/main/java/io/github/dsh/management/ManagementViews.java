package io.github.dsh.management;

import static io.github.dsh.config.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.github.dsh.config.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import io.github.dsh.sources.SourceKind;
import java.time.Instant;
import java.util.*;

/** 从真实领域快照构造页面，不在前端推测权限或制造示例状态。 */
public final class ManagementViews {
  private final Engine engine;
  private final SqliteStore store;
  private final VersionedSettings settings;
  private final ManagementHost host;

  public ManagementViews(
      Engine engine, SqliteStore store, VersionedSettings settings, ManagementHost host) {
    this.engine = engine;
    this.store = store;
    this.settings = settings;
    this.host = host;
  }

  public List<Trigger> triggers() {
    return store.list("trigger", Trigger.class).stream()
        .sorted(Comparator.comparing(Trigger::at).thenComparing(Trigger::id).reversed())
        .toList();
  }

  public Ticket ticket(String id) {
    Ticket t = engine.ticket(id);
    if (t == null) throw new ApiException(404, "Ticket not found");
    return t;
  }

  public ThreadState thread(String key) {
    return engine.threads().stream()
        .filter(t -> t.key.equals(key))
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "Thread not found"));
  }

  public Trigger trigger(String id) {
    Trigger t = engine.trigger(id);
    if (t == null) throw new ApiException(404, "Trigger not found");
    return t;
  }

  public ObjectNode state() {
    ObjectNode setup = (ObjectNode) host.setup().deepCopy();
    setup.set("machine", settings.machine());
    return object(
        "setup",
        setup,
        "load",
        load(),
        "masterId",
        host.masterId(),
        "paused",
        settings.paused(),
        "health",
        host.health());
  }

  public ObjectNode load() {
    long running =
        host.activeCount() >= 0
            ? host.activeCount()
            : engine.tickets().stream()
                .filter(
                    t ->
                        "working".equals(engine.ticketStage(t.id).stage())
                            && engine.ticketStage(t.id).action() == null
                            && t.latestAssignment() != null
                            && t.latestAssignment().status == AssignmentStatus.RUNNING)
                .count();
    long queued =
        engine.tickets().stream()
            .filter(t -> "queued".equals(engine.ticketStage(t.id).action()))
            .count();
    return object(
        "running",
        running,
        "queued",
        queued,
        "maxWorkers",
        settings.value("executor").path("maxWorkers"));
  }

  public ObjectNode ticketLine(Ticket t) {
    TicketStage stage = engine.ticketStage(t.id);
    Assignment latest = t.latestAssignment();
    Report report = latest == null ? null : latest.report;
    boolean retry =
        t.status == TicketStatus.OPEN && (t.delivery != null || engine.executionHeld(t.id));
    ObjectNode result =
        object(
            "id",
            t.id,
            "title",
            t.title,
            "stage",
            stage.stage(),
            "action",
            stage.action(),
            "createdAt",
            date(t.createdAt),
            "updatedAt",
            date(t.updatedAt),
            "summary",
            report == null ? "" : report.summary(),
            "actions",
            object("retry", retry, "stop", t.status == TicketStatus.OPEN),
            "attention",
            t.attention,
            "ended",
            t.ended,
            "sessionId",
            t.sessionId,
            "directory",
            t.directory,
            "stopped",
            t.stopped);
    if (t.attention == null && report != null && report.question() != null)
      result.set("attention", object("question", report.question()));
    if (t.delivery != null) result.set("deliveryError", node(t.delivery));
    if (stage.stage().equals("person") && !t.channels.isEmpty())
      result.set("reply", channelLine(t.channels.getFirst()));
    return result;
  }

  private ObjectNode channelLine(Channel channel) {
    ObjectNode result = (ObjectNode) node(channel);
    JsonNode link = host.channelLink(channel);
    String url = link.path("url").asText("");
    if (link.hasNonNull("triggerId")) {
      Trigger trigger = engine.trigger(link.path("triggerId").asText());
      if (trigger != null && trigger.url() != null) url = trigger.url();
    }
    try {
      java.net.URI target = java.net.URI.create(url);
      if (!("http".equals(target.getScheme()) || "https".equals(target.getScheme()))
          || target.getHost() == null
          || target.getUserInfo() != null) url = "";
    } catch (IllegalArgumentException invalid) {
      url = "";
    }
    result.put("url", url);
    result.set(
        "label",
        link.hasNonNull("label")
            ? link.path("label")
            : SourceForms.label(channel.source(), channel.source()));
    return result;
  }

  public ObjectNode ticketDetail(String id) {
    Ticket t = ticket(id);
    ObjectNode result = ticketLine(t);
    result.set("mandate", node(t.mandate));
    result.put("paused", settings.paused());
    result.set("channels", node(t.channels.stream().map(this::channelLine).toList()));
    result.set("assignments", node(t.assignments));
    List<JsonNode> inputs = new ArrayList<>();
    for (Input input : t.inputs) {
      ObjectNode line = (ObjectNode) node(input);
      line.put("delivered", !t.undelivered.contains(input.id));
      Trigger origin = input.triggerId == null ? null : engine.trigger(input.triggerId);
      if (origin != null) {
        ObjectNode tr =
            object(
                "id",
                origin.id(),
                "actor",
                origin.actor(),
                "human",
                origin.human(),
                "preview",
                preview(origin.text()),
                "thread",
                origin.threads().getFirst().key());
        Disposition disp = engine.triggerDisposition(origin.id());
        if (disp.handbacks.containsKey(id))
          tr.set("handback", object("reason", disp.handbacks.get(id)));
        line.set("trigger", tr);
      }
      inputs.add(line);
    }
    Collections.reverse(inputs);
    result.set("inputs", node(inputs));
    List<JsonNode> threads =
        engine.threads().stream()
            .filter(
                th ->
                    th.subscriptions.stream().anyMatch(s -> s.ticketId().equals(id))
                        || t.follows.stream()
                            .anyMatch(f -> node(f).path("thread").asText().equals(th.key)))
            .map(
                th -> {
                  ObjectNode row = threadLine(th);
                  row.put(
                      "context",
                      th.subscriptions.stream()
                          .anyMatch(s -> s.ticketId().equals(id) && s.contextOnly()));
                  return (JsonNode) row;
                })
            .toList();
    result.set("threads", node(threads));
    result.set("environment", node(host.environment(id)));
    result.set("origin", node(engine.trigger(t.originTriggerId)));
    engine.decisions().stream()
        .filter(d -> id.equals(d.ticketId()))
        .findFirst()
        .ifPresent(d -> result.set("decision", decision(d)));
    return result;
  }

  public ObjectNode tickets(String filter, int offset, int limit) {
    List<JsonNode> rows =
        engine.tickets().stream()
            .filter(
                t ->
                    filter.equals("open")
                        ? t.status == TicketStatus.OPEN
                        : engine.ticketStage(t.id).stage().equals(filter))
            .sorted(Comparator.comparingLong((Ticket t) -> t.updatedAt).reversed())
            .map(t -> (JsonNode) ticketLine(t))
            .toList();
    return page(rows, offset, limit);
  }

  public ObjectNode threadLine(ThreadState th) {
    List<Trigger> items =
        triggers().stream()
            .filter(t -> t.threads().stream().anyMatch(ref -> ref.key().equals(th.key)))
            .toList();
    Trigger newest = items.isEmpty() ? null : items.getFirst();
    JsonNode meta = node(th.metadata);
    Trigger first =
        items.stream()
            .filter(Trigger::human)
            .reduce((a, b) -> b)
            .orElse(items.isEmpty() ? null : items.getLast());
    String fallback =
        first == null ? th.key : first.text().substring(0, Math.min(80, first.text().length()));
    String title = meta.path("label").asText(meta.path("title").asText(fallback));
    String source = th.key.contains(":") ? th.key.substring(0, th.key.indexOf(':')) : th.key;
    List<JsonNode> tickets =
        th.subscriptions.stream()
            .map(Subscription::ticketId)
            .distinct()
            .map(
                id -> {
                  Ticket t = engine.ticket(id);
                  return (JsonNode)
                      object(
                          "id",
                          id,
                          "title",
                          t == null ? id : t.title,
                          "state",
                          t == null ? "unknown" : t.status == TicketStatus.OPEN ? "open" : "ended");
                })
            .toList();
    long pending =
        th.pending.stream()
            .filter(
                id -> {
                  Trigger t = engine.trigger(id);
                  return t == null
                      || !triggerView(t).path("disposition").path("delivered").asBoolean();
                })
            .count();
    String pollError = meta.path("pollError").asText("");
    String stage =
        !pollError.isBlank()
            ? "error"
            : th.evaluating != null
                ? "evaluating"
                : pending > 0
                    ? "queued"
                    : tickets.stream().anyMatch(t -> t.path("state").asText().equals("open"))
                        ? "subscribed"
                        : !tickets.isEmpty() ? "dormant" : "unsubscribed";
    return object(
        "key",
        th.key,
        "title",
        title,
        "source",
        source,
        "stage",
        stage,
        "createdAt",
        date(th.createdAt),
        "lastAt",
        date(th.lastAt > 0 ? th.lastAt : th.createdAt),
        "preview",
        newest == null ? "" : preview(newest.text()),
        "latest",
        newest == null
            ? null
            : object(
                "actor",
                object("source", newest.source(), "id", newest.actor()),
                "human",
                newest.human(),
                "at",
                newest.at(),
                "preview",
                preview(newest.text())),
        "subscriptions",
        th.subscriptions.size(),
        "tickets",
        tickets,
        "pending",
        pending,
        "evaluating",
        th.evaluating,
        "triggers",
        items.size(),
        "pollError",
        pollError,
        "facts",
        meta.path("facts").isMissingNode() ? object() : meta.path("facts"),
        "url",
        meta.path("url").asText(newest == null ? null : newest.url()));
  }

  public ObjectNode threads(String filter, int offset, int limit) {
    String since = Instant.now().minusSeconds(86400).toString();
    List<JsonNode> rows =
        engine.threads().stream()
            .filter(
                th ->
                    switch (filter) {
                      case "subscribed" ->
                          th.subscriptions.stream()
                              .anyMatch(
                                  s -> {
                                    Ticket t = engine.ticket(s.ticketId());
                                    return t != null && t.status == TicketStatus.OPEN;
                                  });
                      case "evaluating" -> th.evaluating != null || !th.pending.isEmpty();
                      case "recent" ->
                          triggers().stream()
                              .anyMatch(
                                  t ->
                                      t.at().compareTo(since) >= 0
                                          && t.threads().stream()
                                              .anyMatch(ref -> ref.key().equals(th.key)));
                      default -> true;
                    })
            .map(this::threadLine)
            .sorted(Comparator.comparing((ObjectNode n) -> n.path("lastAt").asText()).reversed())
            .map(n -> (JsonNode) n)
            .toList();
    return page(rows, offset, limit);
  }

  public ObjectNode triggerView(Trigger trigger) {
    ObjectNode result = (ObjectNode) node(trigger);
    result.put("preview", preview(trigger.text()));
    result.put("thread", trigger.threads().getFirst().key());
    Disposition disposition = engine.triggerDisposition(trigger.id());
    ObjectNode d = (ObjectNode) node(disposition);
    ThreadState home =
        engine.threads().stream()
            .filter(t -> t.key.equals(trigger.threads().getFirst().key()))
            .findFirst()
            .orElse(null);
    boolean delivered =
        trigger.human()
            && disposition.owners.stream()
                .anyMatch(
                    id -> {
                      Ticket t = engine.ticket(id);
                      return t != null
                          && !t.stopped
                          && t.status == TicketStatus.OPEN
                          && !disposition.context.contains(id)
                          && !disposition.handbacks.containsKey(id);
                    });
    boolean queued = home != null && home.pending.contains(trigger.id()) && !delivered,
        evaluating =
            home != null
                && home.evaluating != null
                && home.evaluating.triggerIds().contains(trigger.id());
    List<Decision> decisions =
        engine.decisions().stream()
            .filter(v -> v.triggerIds().contains(trigger.id()))
            .sorted(Comparator.comparingLong(Decision::at))
            .toList();
    Decision latest = decisions.isEmpty() ? null : decisions.getLast();
    boolean decided =
        !disposition.acked.isEmpty()
            || (latest != null
                && latest.kind() != DecisionKind.UNAVAILABLE
                && latest.kind() != DecisionKind.STOP);
    boolean unjudged =
        disposition.filtered != null
            || latest != null && latest.kind() == DecisionKind.UNAVAILABLE
            || delivered
            || !disposition.fanout.isEmpty() && !decided
            || latest != null && !decided;
    d.put("queued", queued);
    d.put("evaluating", evaluating);
    d.put("delivered", delivered);
    result.set("disposition", d);
    result.set("actions", object("requeue", trigger.human() && !queued && !evaluating && unjudged));
    result.set("decisions", node(decisions.stream().map(this::decision).toList()));
    return result;
  }

  public ObjectNode threadDetail(String key, String before, String around, int limit) {
    ThreadState th = thread(key);
    List<Trigger> all =
        triggers().stream()
            .filter(t -> t.threads().stream().anyMatch(ref -> ref.key().equals(key)))
            .toList();
    if (around != null && !around.isBlank()) {
      Trigger target = trigger(around);
      if (target.threads().stream().noneMatch(ref -> ref.key().equals(key)))
        throw new ApiException(400, "Anchor trigger belongs to another thread");
      int index = all.indexOf(target);
      int start = Math.max(0, index - limit / 2);
      all = all.subList(start, all.size());
    } else if (before != null) {
      String[] cursor = before.split("\\|", 2);
      String at = cursor[0], id = cursor.length > 1 ? cursor[1] : "";
      all =
          all.stream()
              .filter(
                  t -> t.at().compareTo(at) < 0 || t.at().equals(at) && t.id().compareTo(id) < 0)
              .toList();
    }
    List<Trigger> selected = new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
    boolean more = all.size() > limit;
    Collections.reverse(selected);
    ObjectNode out = threadLine(th);
    out.set("timeline", node(selected.stream().map(this::triggerView).toList()));
    out.set(
        "tickets",
        node(
            th.subscriptions.stream()
                .map(Subscription::ticketId)
                .distinct()
                .map(engine::ticket)
                .filter(Objects::nonNull)
                .map(this::ticketLine)
                .toList()));
    out.set(
        "decisions",
        node(
            engine.decisions().stream()
                .filter(d -> key.equals(d.threadKey()))
                .sorted(Comparator.comparingLong(Decision::at).reversed())
                .map(this::decision)
                .toList()));
    out.set(
        "correlated",
        node(
            th.correlated.stream()
                .map(
                    other -> {
                      ThreadState value =
                          engine.threads().stream()
                              .filter(t -> t.key.equals(other))
                              .findFirst()
                              .orElse(null);
                      return object(
                          "key",
                          other,
                          "title",
                          value == null ? other : threadLine(value).path("title").asText());
                    })
                .toList()));
    out.set(
        "context",
        node(
            th.subscriptions.stream()
                .filter(Subscription::contextOnly)
                .map(Subscription::ticketId)
                .toList()));
    out.set(
        "earlier",
        node(
            more && !selected.isEmpty()
                ? selected.getFirst().at() + "|" + selected.getFirst().id()
                : null));
    return out;
  }

  public ObjectNode decision(Decision d) {
    ObjectNode row = (ObjectNode) node(d);
    JsonNode audit = node(d.audit());
    String outcome =
        d.kind() == DecisionKind.IGNORE && audit.path("would_accept").asBoolean()
            ? "wouldAccept"
            : d.kind().name().toLowerCase(Locale.ROOT);
    row.put("outcome", outcome);
    row.put("thread", d.threadKey());
    row.put("at", date(d.at()));
    Ticket ticket = d.ticketId() == null ? null : engine.ticket(d.ticketId());
    row.set("ticket", ticket == null ? null : object("id", ticket.id, "title", ticket.title));
    List<JsonNode> rules = new ArrayList<>();
    JsonNode assessments =
        audit.has("rule_assessments") ? audit.path("rule_assessments") : audit.path("matches");
    for (JsonNode judged : assessments) {
      String id = judged.path("rule_id").asText(), name = id;
      for (JsonNode rule : settings.value("policy").path("rules"))
        if (rule.path("id").asText().equals(id)) name = rule.path("name").asText();
      rules.add(
          object(
              "id",
              id,
              "name",
              name,
              "matched",
              judged.path("matched").asBoolean(true),
              "reason",
              judged.path("reason").asText()));
    }
    if (rules.isEmpty() && ticket != null)
      for (String id : ticket.mandate.matchedRules())
        rules.add(object("id", id, "name", id, "matched", true, "reason", ""));
    row.set("rules", node(rules));
    if (audit.has("direction")) row.set("direction", audit.get("direction"));
    if (!d.triggerIds().isEmpty()) {
      Trigger trigger = engine.trigger(d.triggerIds().getFirst());
      if (trigger != null) row.put("preview", preview(trigger.text()));
    }
    return row;
  }

  public ObjectNode decisions(String kind, boolean day, int offset, int limit) {
    Set<String> kinds = new HashSet<>(Arrays.asList(kind.toLowerCase(Locale.ROOT).split(",")));
    long since = day ? System.currentTimeMillis() - 86400000L : 0;
    return page(
        engine.decisions().stream()
            .filter(
                d ->
                    d.at() >= since
                        && (kind.isBlank()
                            || kinds.contains(d.kind().name().toLowerCase(Locale.ROOT))
                            || (kinds.contains("would_accept") || kinds.contains("wouldaccept"))
                                && d.kind() == DecisionKind.IGNORE
                                && node(d.audit()).path("would_accept").asBoolean()))
            .sorted(Comparator.comparingLong(Decision::at).reversed())
            .map(d -> (JsonNode) decision(d))
            .toList(),
        offset,
        limit);
  }

  public ObjectNode search(String text) {
    String q = text.strip().toLowerCase(Locale.ROOT);
    if (q.isEmpty()) return object("tickets", List.of(), "items", List.of(), "threads", List.of());
    JsonNode sourceMatches = host.findSource(text);
    Set<String> terms = new LinkedHashSet<>(List.of(q));
    sourceMatches.path("terms").forEach(term -> terms.add(term.asText().toLowerCase(Locale.ROOT)));
    java.util.regex.Matcher reference =
        java.util.regex.Pattern.compile("(?:^#?|github\\.com/[^/]+/[^/]+/(?:issues|pull)/)([0-9]+)")
            .matcher(q);
    String referenceNumber = reference.find() ? reference.group(1) : null;
    host.sourceLinks()
        .fields()
        .forEachRemaining(
            e -> {
              if (e.getValue().asText().equalsIgnoreCase(text))
                terms.add(e.getKey().toLowerCase(Locale.ROOT));
            });
    List<Trigger> found =
        triggers().stream()
            .filter(
                t ->
                    terms.stream()
                        .anyMatch(
                            term -> node(t).toString().toLowerCase(Locale.ROOT).contains(term)))
            .limit(100)
            .toList();
    Set<String> keys = new HashSet<>();
    sourceMatches.path("threads").forEach(key -> keys.add(key.asText()));
    if (referenceNumber != null) keys.add("github:" + referenceNumber);
    found.forEach(t -> t.threads().forEach(r -> keys.add(r.key())));
    List<JsonNode> threads =
        engine.threads().stream()
            .filter(
                t ->
                    keys.contains(t.key)
                        || node(t).toString().toLowerCase(Locale.ROOT).contains(q)
                        || (referenceNumber != null
                            && t.key.startsWith("github:")
                            && t.key.matches(
                                ".*(?:[:/#]|^)(?:issue:|pull:)?" + referenceNumber + "$")))
            .limit(20)
            .map(t -> (JsonNode) threadLine(t))
            .toList();
    Set<String> ids = new HashSet<>();
    engine.threads().stream()
        .filter(t -> keys.contains(t.key))
        .forEach(t -> t.subscriptions.forEach(s -> ids.add(s.ticketId())));
    return object(
        "tickets",
        engine.tickets().stream()
            .filter(
                t ->
                    t.title.toLowerCase(Locale.ROOT).contains(q)
                        || t.id.toLowerCase(Locale.ROOT).contains(q)
                        || ids.contains(t.id))
            .limit(10)
            .map(this::ticketLine)
            .toList(),
        "items",
        found.stream().limit(20).map(this::triggerView).toList(),
        "threads",
        threads);
  }

  public List<JsonNode> sources() {
    List<JsonNode> result = new ArrayList<>();
    String since = Instant.now().minusSeconds(86400).toString();
    JsonNode setup = host.setup();
    for (String source : settings.sourceIds()) {
      JsonNode block = settings.value("source:" + source), details = host.sourceDetails(source);
      SourceKind kind = settings.sourceKind(source);
      List<JsonNode> healthParts = sourceHealth(source);
      JsonNode health =
          healthParts.stream()
              .filter(p -> p.path("state").asText().equals("error"))
              .findFirst()
              .orElseGet(
                  () ->
                      healthParts.stream()
                          .filter(p -> p.path("state").asText().equals("starting"))
                          .findFirst()
                          .orElseGet(
                              () ->
                                  healthParts.stream()
                                      .filter(p -> p.path("state").asText().equals("paused"))
                                      .findFirst()
                                      .orElse(object("state", "ready"))));
      boolean enabled = block.path("enabled").asBoolean();
      if (enabled && health.path("code").asText().equals("sourceOff"))
        health = object("state", "starting", "detail", "");
      JsonNode problem = details.hasNonNull("problem") ? details.path("problem") : null;
      for (String key : List.of("problem", "feishuProblem"))
        if (Set.of(source, "source:" + source).contains(setup.path(key).path("place").asText()))
          problem = setup.path(key);
      String status =
          kind == null || !details.path("installed").asBoolean(true)
              ? "missing"
              : !enabled
                  ? "off"
                  : problem != null || health.path("state").asText().equals("error")
                      ? "error"
                      : setup.hasNonNull("problem")
                          ? "setup"
                          : health.path("state").asText("starting");
      List<Trigger> recent =
          triggers().stream()
              .filter(t -> t.source().equals(source) && t.at().compareTo(since) >= 0)
              .toList();
      List<JsonNode> filtered =
          recent.stream()
              .filter(t -> engine.triggerDisposition(t.id()).filtered != null)
              .map(
                  t -> {
                    ObjectNode row = triggerView(t);
                    row.put("reason", engine.triggerDisposition(t.id()).filtered);
                    return (JsonNode) row;
                  })
              .toList();
      ObjectNode row =
          object(
              "id",
              source,
              "title",
              kind == null ? SourceForms.label(source, source) : kind.title(),
              "installed",
              kind != null && details.path("installed").asBoolean(true),
              "enabled",
              enabled,
              "state",
              status,
              "health",
              healthParts,
              "stats",
              object(
                  "received",
                  recent.size(),
                  "filtered",
                  filtered.size(),
                  "lastAt",
                  recent.isEmpty() ? "" : recent.getFirst().at()),
              "filtered",
              filtered.stream().limit(5).toList(),
              "dropped",
              dropped(source));
      if (problem != null) row.set("problem", problem);
      result.add(row);
    }
    return result;
  }

  private List<JsonNode> sourceHealth(String source) {
    SourceKind kind = settings.sourceKind(source);
    if (kind == null) return List.of();
    List<JsonNode> parts = new ArrayList<>();
    for (String name : kind.health()) {
      JsonNode entry = host.health().path(name);
      if (entry.isMissingNode()
          || settings.value("source:" + source).path("enabled").asBoolean()
              && entry.path("code").asText().equals("sourceOff"))
        entry = object("state", "starting", "detail", "");
      ObjectNode row = (ObjectNode) entry.deepCopy();
      row.put("name", name);
      parts.add(row);
    }
    return parts;
  }

  private List<JsonNode> dropped(String source) {
    long since = System.currentTimeMillis() - 86400000L;
    List<JsonNode> result = new ArrayList<>();
    for (OutboxMessage message : engine.listOutbox()) {
      JsonNode raw = node(message);
      long at = raw.path("droppedAt").asLong();
      if (!"dropped".equals(message.status())
          || !message.channel().source().equals(source)
          || at < since) continue;
      Ticket ticket = message.ticketId() == null ? null : engine.ticket(message.ticketId());
      result.add(
          object(
              "id",
              message.id(),
              "channel",
              message.channel(),
              "preview",
              preview(message.text()),
              "ticket",
              ticket == null ? null : object("id", ticket.id, "title", ticket.title),
              "thread",
              message.channel().source() + ":" + message.channel().thread(),
              "dropped",
              object(
                  "at",
                  date(at),
                  "reason",
                  raw.path("dropReason").asText(raw.path("droppedReason").asText()))));
    }
    result.sort(
        Comparator.comparing((JsonNode n) -> n.path("dropped").path("at").asText()).reversed());
    return result;
  }

  public ObjectNode overview() {
    List<JsonNode> problems = new ArrayList<>();
    JsonNode setup = host.setup();
    if (setup.hasNonNull("problem"))
      problems.add(object("kind", "setup", "detail", setup.path("problem")));
    else if (setup.hasNonNull("feishuProblem"))
      problems.add(object("kind", "setup", "detail", setup.path("feishuProblem")));
    if (sources().stream()
        .noneMatch(s -> s.path("enabled").asBoolean() && s.path("installed").asBoolean()))
      problems.add(object("kind", "no-source"));
    boolean allowed = false;
    for (JsonNode source : settings.value("policy").path("sources"))
      if (!source.path("allowed").isEmpty()) allowed = true;
    if (!allowed) problems.add(object("kind", "no-allowed"));
    if (settings.paused()) problems.add(object("kind", "executor-paused"));
    Map<String, String> sourceHealthOwners = new HashMap<>();
    for (String id : settings.sourceIds()) {
      SourceKind kind = settings.sourceKind(id);
      if (kind != null) kind.health().forEach(name -> sourceHealthOwners.put(name, id));
    }
    host.health()
        .fields()
        .forEachRemaining(
            e -> {
              if (e.getValue().path("state").asText().equals("error")
                  && !e.getKey().startsWith("sync:")
                  && !e.getKey().equals("evals")) {
                String source = sourceHealthOwners.get(e.getKey());
                if (source != null) {
                  if (settings.value("source:" + source).path("enabled").asBoolean())
                    problems.add(
                        object(
                            "kind",
                            "source-error",
                            "source",
                            object("id", source, "health", e.getValue()),
                            "detail",
                            e.getValue().path("detail")));
                } else
                  problems.add(
                      object(
                          "kind",
                          "runtime-error",
                          "name",
                          e.getKey(),
                          "detail",
                          e.getValue().path("detail")));
              }
            });
    for (Effect effect : store.effects())
      if (effect.kind() == EffectKind.REPLY
          && effect.attempts() > 0
          && effect.lastError() != null) {
        OutboxMessage message = engine.outboxMessage(effect.payload().get("messageId"));
        if (message != null && message.status().equals("pending"))
          problems.add(
              object(
                  "kind",
                  "outgoing",
                  "outgoing",
                  object(
                      "id",
                      message.id(),
                      "channel",
                      message.channel(),
                      "text",
                      preview(message.text()),
                      "attempts",
                      effect.attempts(),
                      "error",
                      effect.lastError()),
                  "detail",
                  effect.lastError()));
      }
    long dayAgo = System.currentTimeMillis() - 86400000L;
    for (ThreadState thread : engine.threads()) {
      if (!settings.paused()
          && thread.evaluating != null
          && thread.evaluating.deliveredAt() != null
          && thread.evaluating.deadline() < System.currentTimeMillis())
        problems.add(object("kind", "eval-overdue", "thread", threadLine(thread)));
      Decision latest =
          engine.decisions().stream()
              .filter(d -> thread.key.equals(d.threadKey()))
              .max(Comparator.comparingLong(Decision::at))
              .orElse(null);
      if (latest != null
          && latest.at() >= dayAgo
          && latest.kind() == DecisionKind.UNAVAILABLE
          && !latest.dismissed()
          && thread.evaluating == null
          && thread.pending.isEmpty())
        problems.add(
            object(
                "kind",
                "eval-unavailable",
                "decisionId",
                latest.id(),
                "triggerId",
                latest.triggerIds().isEmpty() ? "" : latest.triggerIds().getFirst(),
                "thread",
                threadLine(thread),
                "reason",
                latest.reason()));
    }
    List<JsonNode> person =
        engine.tickets().stream()
            .filter(t -> engine.ticketStage(t.id).stage().equals("person"))
            .map(t -> (JsonNode) ticketLine(t))
            .toList();
    String since = Instant.now().minusSeconds(86400).toString();
    List<Trigger> recent = triggers().stream().filter(t -> t.at().compareTo(since) >= 0).toList();
    long filtered =
        recent.stream().filter(t -> engine.triggerDisposition(t.id()).filtered != null).count();
    Set<String> recentIds = new HashSet<>(), passedIds = new HashSet<>();
    for (Trigger trigger : recent) {
      recentIds.add(trigger.id());
      if (engine.triggerDisposition(trigger.id()).filtered == null) passedIds.add(trigger.id());
    }
    long judged =
        engine.decisions().stream()
            .flatMap(d -> d.triggerIds().stream())
            .filter(passedIds::contains)
            .distinct()
            .count();
    long accepted =
        engine.decisions().stream()
            .filter(
                d ->
                    (d.kind() == DecisionKind.ACCEPT || d.kind().name().equals("REOPEN"))
                        && d.triggerIds().stream().anyMatch(recentIds::contains))
            .count();
    ObjectNode load = load();
    load.put(
        "idle",
        engine.tickets().stream()
            .filter(t -> engine.ticketStage(t.id).stage().equals("idle"))
            .count());
    return object(
        "problems",
        problems,
        "person",
        person,
        "executor",
        load,
        "flow",
        object(
            "received",
            recent.size(),
            "filtered",
            filtered,
            "triggers",
            recent.size() - filtered,
            "judged",
            judged,
            "accepted",
            accepted,
            "evaluating",
            engine.threads().stream().filter(t -> t.evaluating != null).count(),
            "dropped",
            sources().stream().mapToInt(s -> s.path("dropped").size()).sum()),
        "policyVersion",
        settings.policyVersion());
  }

  public ObjectNode settings() {
    ObjectNode blocks = object();
    for (String name : List.of("policy", "eval", "executor", "deployment"))
      blocks.set(name, settings.block(name));
    List<JsonNode> sources = new ArrayList<>();
    Map<String, JsonNode> variables = new LinkedHashMap<>();
    host.environment(null).forEach(variable -> variables.put(variable.get("key"), node(variable)));
    for (String id : settings.sourceIds()) {
      SourceKind kind = settings.sourceKind(id);
      ObjectNode row = settings.block("source:" + id);
      row.put("id", id);
      row.put("kind", id);
      row.put(
          "installed", kind != null && host.sourceDetails(id).path("installed").asBoolean(true));
      if (kind != null) {
        JsonNode descriptor = kind.descriptor(row.path("value").path("config"), host.setup());
        for (String key :
            List.of(
                "title",
                "description",
                "fields",
                "facts",
                "policy",
                "environment",
                "refreshLabels")) if (descriptor.has(key)) row.set(key, descriptor.get(key));
        row.set("restarts", node(kind.restartKeys()));
        for (JsonNode variable : descriptor.path("environment").path("variables"))
          variables.putIfAbsent(variable.path("key").asText(), variable);
        if (row.path("policy").isObject())
          ((ObjectNode) row.path("policy")).set("health", host.health().path("sync:" + id));
      } else {
        row.set("title", SourceForms.label(id, id));
        row.set("fields", array(List.of()));
        row.set(
            "description",
            SourceForms.label(
                "来源插件未安装；保留原配置", "Source plugin not installed; stored configuration is preserved"));
      }
      row.set("runtime", host.sourceDetails(id));
      JsonNode labels = store.getState("source:" + id, "labels", JsonNode.class);
      row.set("labels", labels == null ? object() : labels);
      sources.add(row);
    }
    String root = settings.value("executor").path("directory").asText();
    if (root.isBlank()) root = settings.stateDir().resolve("work").toString();
    return object(
        "blocks",
        blocks,
        "sources",
        sources,
        "policyVersion",
        settings.policyVersion(),
        "environment",
        object("root", root, "variables", variables.values()));
  }

  public static ObjectNode page(List<? extends JsonNode> rows, int offset, int limit) {
    int start = Math.min(Math.max(0, offset), rows.size()),
        end = Math.min(rows.size(), start + limit);
    return object(
        "items",
        rows.subList(start, end),
        "next_offset",
        end < rows.size() ? end : null,
        "total",
        rows.size());
  }

  private static String date(long millis) {
    return millis <= 0 ? "" : Instant.ofEpochMilli(millis).toString();
  }

  private static String preview(String text) {
    return text.length() <= 180 ? text : text.substring(0, 180) + "…";
  }
}
