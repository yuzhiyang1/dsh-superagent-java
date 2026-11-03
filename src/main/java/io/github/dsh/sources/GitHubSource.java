package io.github.dsh.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsh.adapters.*;
import io.github.dsh.core.Model.*;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** 通知、全仓事件流、订阅线程、PR 生命周期和 CI 的持久化轮询器。各流失败隔离。 */
public final class GitHubSource implements SourceInstance {
  public record Config(
      boolean enabled,
      boolean writesEnabled,
      String repository,
      String botLogin,
      boolean observeIssues,
      boolean observePulls,
      boolean observeComments,
      boolean observeCi,
      int pollSeconds,
      int maxPages,
      int failuresBeforeError,
      String cli) {
    public Config {
      if (cli == null) cli = "";
      if (cli.contains("\n") || cli.contains("\r"))
        throw new IllegalArgumentException("Invalid CLI executable");
      if (repository == null
          || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
          || repository.contains("..")) throw new IllegalArgumentException("Invalid repository");
      if (botLogin == null || botLogin.isBlank())
        throw new IllegalArgumentException("Bot login required");
      if (pollSeconds == 0) pollSeconds = 60;
      if (maxPages == 0) maxPages = 100;
      if (failuresBeforeError == 0) failuresBeforeError = 3;
      if (pollSeconds < 60
          || pollSeconds > 3600
          || maxPages < 1
          || maxPages > 1000
          || failuresBeforeError < 1
          || failuresBeforeError > 20) throw new IllegalArgumentException("Invalid source limits");
    }

    public Config(
        boolean enabled,
        boolean writesEnabled,
        String repository,
        String botLogin,
        boolean observeIssues,
        boolean observePulls,
        boolean observeComments,
        boolean observeCi,
        int pollSeconds,
        int maxPages,
        int failuresBeforeError) {
      this(
          enabled,
          writesEnabled,
          repository,
          botLogin,
          observeIssues,
          observePulls,
          observeComments,
          observeCi,
          pollSeconds,
          maxPages,
          failuresBeforeError,
          "");
    }

    public static Config defaults(String repository, String bot) {
      return new Config(true, false, repository, bot, false, false, false, false, 60, 100, 3);
    }
  }

  private final String id, token;
  private final URI base;
  private volatile Config config;

  public GitHubSource(String id, URI base, String token, Config config) {
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
    return "github";
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

  private GitHubProtocol api() {
    Config c = config;
    return new GitHubProtocol(base, token, c.repository(), id, c.botLogin(), c.maxPages(), c.cli());
  }

  private ObjectNode state(SourceContext ctx, String key) {
    JsonNode n = ctx.state(id, key, JsonNode.class);
    return n != null && n.isObject()
        ? ((ObjectNode) n).deepCopy()
        : HttpJsonTransport.JSON.createObjectNode();
  }

  private void checkEnabled() {
    if (!enabled()) throw new IllegalStateException("Source disabled during poll");
  }

  @Override
  public synchronized PollResult poll(SourceContext ctx) {
    installScope(ctx);
    if (!enabled()) return new PollResult(0, config.pollSeconds(), List.of());
    Round round = new Round(ctx.now());
    GitHubProtocol api = api();
    run(round, "notifications", () -> notifications(ctx, api, round));
    run(
        round,
        "issues",
        () ->
            stream(
                ctx,
                api,
                round,
                "issues",
                config.observeIssues() || config.observePulls(),
                "issues?state=all"));
    run(
        round,
        "comments",
        () -> stream(ctx, api, round, "comments", config.observeComments(), "issues/comments?"));
    run(
        round,
        "review-comments",
        () ->
            stream(
                ctx, api, round, "review-comments", config.observeComments(), "pulls/comments?"));
    run(
        round,
        "default-ci",
        () -> {
          if (config.observeCi()) branchCi(ctx, api, round);
          else {
            ObjectNode prior = state(ctx, "branch-ci");
            prior.remove(List.of("branch", "failures"));
            ctx.state(id, "branch-ci", prior);
          }
        });
    // 持久化来源元数据与 Subscription，而不是只记内存中的 watch 列表。
    Set<Integer> numbers = new LinkedHashSet<>();
    for (ThreadState thread : ctx.subscribed(id))
      if (ctx.polled(thread)) {
        int n = number(thread.key);
        if (n > 0) numbers.add(n);
      }
    for (Ticket ticket : ctx.engine().tickets())
      if (ticket.status == TicketStatus.OPEN)
        for (Follow follow : ticket.follows)
          if (follow.thread.startsWith(id + ":") && follow.closed == null) {
            int n = number(follow.thread);
            if (n > 0) numbers.add(n);
          }
    for (int n : numbers)
      run(
          round,
          "thread:" + n,
          () -> {
            if (!round.read.contains(n)) {
              String since =
                  state(ctx, "thread:" + n)
                      .path("since")
                      .asText(initialThreadTime(ctx, n, round.started));
              readThread(ctx, api, round, n, false, since);
            }
            JsonNode pull = round.pulls.get(n);
            if (pull != null && !closed(pull)) prCi(ctx, api, round, n, pull);
          });
    ObjectNode health = state(ctx, "health");
    int failures = round.errors.isEmpty() ? 0 : health.path("failures").asInt() + 1;
    health.put("failures", failures);
    health.put(
        "state",
        round.errors.isEmpty()
            ? "ready"
            : failures >= config.failuresBeforeError() ? "error" : "retrying");
    health.put("at", ctx.now().toString());
    health.set("errors", HttpJsonTransport.JSON.valueToTree(round.errors));
    health.put("received", round.received);
    health.put("skipped", round.skipped);
    ctx.state(id, "health", health);
    return new PollResult(
        round.received, Math.max(config.pollSeconds(), round.interval), round.errors);
  }

  private interface Checked {
    void run() throws Exception;
  }

  private void run(Round round, String part, Checked action) {
    try {
      checkEnabled();
      action.run();
    } catch (Exception error) {
      round.errors.add(
          part
              + ": "
              + error.getClass().getSimpleName()
              + (error instanceof HttpJsonTransport.ApiException e ? " HTTP " + e.status() : ""));
    }
  }

  private static final class Round {
    final Instant started;
    int received, skipped, interval = 60;
    final List<String> errors = new ArrayList<>();
    final Set<Integer> read = new HashSet<>();
    final Map<Integer, JsonNode> pulls = new HashMap<>();

    Round(Instant now) {
      started = now;
    }
  }

  private void ingest(SourceContext ctx, ReceivedEvent event, Round round) {
    if (event == null) return;
    checkEnabled();
    String kind = Objects.toString(event.metadata().get("kind"), "");
    String scope =
        event.metadata().containsKey("branch")
            ? "ci"
            : Boolean.TRUE.equals(event.metadata().get("ci"))
                ? "pr-ci"
                : kind.equals("issue") ? "issues" : kind.equals("pull") ? "pulls" : "comments";
    boolean observed =
        switch (scope) {
          case "issues" -> config.observeIssues();
          case "pulls" -> config.observePulls();
          case "ci" -> config.observeCi();
          case "pr-ci" -> false;
          default -> config.observeComments();
        };
    ctx.engine().receive(SourceBridge.toTrigger(event, scope, observed));
    ctx.engine().describeThread(event.threadId(), event.metadata());
    round.received++;
  }

  private void notifications(SourceContext ctx, GitHubProtocol api, Round round) throws Exception {
    ObjectNode prior = state(ctx, "notifications");
    String since = prior.path("since").asText(round.started.toString());
    String path = "notifications?all=true&per_page=100&since=" + GitHubProtocol.encode(since);
    var response = api.detail(path, Map.of());
    if (!response.body().isArray()) throw new IOException("Invalid notifications");
    try {
      round.interval = Math.max(60, Integer.parseInt(response.header("x-poll-interval")));
    } catch (NumberFormatException ignored) {
    }
    List<JsonNode> notifications = new ArrayList<>();
    response.body().forEach(notifications::add);
    if (response.body().size() == 100 || response.header("link").contains("rel=\"next\""))
      notifications = api.pages(path, null);
    ObjectNode retries =
        prior.path("retries").isObject()
            ? ((ObjectNode) prior.path("retries")).deepCopy()
            : HttpJsonTransport.JSON.createObjectNode();
    LinkedHashMap<Integer, ObjectNode> pending = new LinkedHashMap<>();
    retries
        .fields()
        .forEachRemaining(
            e -> pending.put(Integer.parseInt(e.getKey()), (ObjectNode) e.getValue()));
    Collections.reverse(notifications);
    Pattern address =
        Pattern.compile(
            "https://api\\.github\\.com/repos/"
                + Pattern.quote(config.repository())
                + "/(issues|pulls)/([1-9][0-9]*)",
            Pattern.CASE_INSENSITIVE);
    for (JsonNode notification : notifications) {
      if (!notification
          .path("repository")
          .path("full_name")
          .asText()
          .equalsIgnoreCase(config.repository())) continue;
      Matcher m = address.matcher(notification.path("subject").path("url").asText());
      if (!m.matches()) continue;
      int number = Integer.parseInt(m.group(2));
      ObjectNode item =
          HttpJsonTransport.JSON
              .createObjectNode()
              .put("since", state(ctx, "thread:" + number).path("since").asText(since))
              .put("pr", m.group(1).equals("pulls"));
      pending.putIfAbsent(number, item);
    }
    for (var entry : pending.entrySet()) {
      checkEnabled();
      int n = entry.getKey();
      ObjectNode item = entry.getValue();
      try {
        readThread(ctx, api, round, n, item.path("pr").asBoolean(), item.path("since").asText());
        retries.remove(Integer.toString(n));
      } catch (Exception error) {
        ObjectNode retry = item.deepCopy();
        String failedAt = retry.path("failedAt").asText(round.started.toString());
        retry.put("failedAt", failedAt);
        retry.put("attempts", retry.path("attempts").asInt() + 1);
        boolean gone = error instanceof HttpJsonTransport.ApiException e && e.status() == 410;
        boolean expired = Duration.between(Instant.parse(failedAt), round.started).toHours() >= 24;
        if (gone || expired) retries.remove(Integer.toString(n));
        else {
          retries.set(Integer.toString(n), retry);
          round.errors.add("notification #" + n + ": " + error.getClass().getSimpleName());
        }
        ctx.engine()
            .describeThread(
                id + ":" + n,
                Map.of(
                    "pollError",
                    gone ? "gone" : expired ? "retry_expired" : error.getClass().getSimpleName()));
      }
    }
    ObjectNode next =
        HttpJsonTransport.JSON
            .createObjectNode()
            .put("since", round.started.minusSeconds(5).toString());
    next.set("retries", retries);
    ctx.store()
        .atomic(
            () -> {
              ctx.state(id, "notifications", next);
              return null;
            });
  }

  private void stream(
      SourceContext ctx, GitHubProtocol api, Round round, String name, boolean on, String path)
      throws Exception {
    if (!on) {
      ctx.state(id, "stream:" + name, null);
      return;
    }
    ObjectNode prior = state(ctx, "stream:" + name);
    ObjectNode started =
        prior.path("started").isObject()
            ? ((ObjectNode) prior.path("started")).deepCopy()
            : HttpJsonTransport.JSON.createObjectNode();
    Set<String> active = name.equals("issues") ? new LinkedHashSet<>() : Set.of("comments");
    if (name.equals("issues")) {
      if (config.observeIssues()) active.add("issues");
      if (config.observePulls()) active.add("pulls");
    }
    for (String kind : List.of("issues", "pulls", "comments")) {
      if (active.contains(kind)) {
        if (!started.has(kind)) started.put(kind, round.started.toString());
      } else started.remove(kind);
    }
    Instant horizon = round.started.minus(Duration.ofDays(14));
    Instant since = Instant.parse(prior.path("since").asText(round.started.toString()));
    if (since.isBefore(horizon)) since = horizon;
    String query =
        path
            + "&sort=updated&direction=asc&per_page=100&since="
            + GitHubProtocol.encode(since.toString());
    Map<String, String> conditional =
        prior.path("since").asText().equals(since.toString()) && prior.has("etag")
            ? Map.of("If-None-Match", prior.path("etag").asText())
            : Map.of();
    var first = api.detail(query, conditional);
    if (first.status() == 304) return;
    if (!first.body().isArray()) throw new IOException("Invalid stream page");
    List<JsonNode> items = new ArrayList<>();
    first.body().forEach(items::add);
    if (first.body().size() == 100) {
      for (int p = 2; ; p++) {
        if (p > config.maxPages()) throw new IOException("Stream page limit");
        JsonNode page = api.get(query + "&page=" + p);
        if (!page.isArray()) throw new IOException("Invalid stream page");
        page.forEach(items::add);
        if (page.size() < 100) break;
      }
    }
    List<ReceivedEvent> events = new ArrayList<>();
    Instant newest = since;
    for (JsonNode item : items) {
      checkEnabled();
      try {
        Instant updated = Instant.parse(GitHubProtocol.first(item, "updated_at", "created_at"));
        if (updated.isAfter(newest)) newest = updated;
        if (name.equals("issues")) {
          boolean pr = item.hasNonNull("pull_request");
          String kind = pr ? "pulls" : "issues";
          if (!started.has(kind)
              || Instant.parse(item.path("created_at").asText())
                  .isBefore(Instant.parse(started.path(kind).asText()))) continue;
          ReceivedEvent event = api.issue(item, pr);
          if (event != null) events.add(event);
        } else {
          if (updated.isBefore(Instant.parse(started.path("comments").asText()))) continue;
          String url =
              item.path(name.equals("comments") ? "issue_url" : "pull_request_url").asText();
          Matcher m = Pattern.compile("/(?:issues|pulls)/([1-9][0-9]*)$").matcher(url);
          if (!m.find()) continue;
          ReceivedEvent event =
              api.comment(
                  item,
                  Integer.parseInt(m.group(1)),
                  !name.equals("comments") || item.path("html_url").asText().contains("/pull/"),
                  name.equals("comments") ? "conversation" : "inline");
          if (event != null) events.add(event);
        }
      } catch (IllegalArgumentException malformed) {
        round.skipped++;
      }
    }
    Instant nextTime = newest.minusSeconds(5).isAfter(since) ? newest.minusSeconds(5) : since;
    ObjectNode next = HttpJsonTransport.JSON.createObjectNode().put("since", nextTime.toString());
    next.set("started", started);
    if (first.body().size() < 100 && nextTime.equals(since) && !first.header("etag").isBlank())
      next.put("etag", first.header("etag"));
    events.sort(Comparator.comparing(ReceivedEvent::at).thenComparing(ReceivedEvent::eventId));
    ctx.store()
        .atomic(
            () -> {
              events.forEach(e -> ingest(ctx, e, round));
              ctx.state(id, "stream:" + name, next);
              return null;
            });
  }

  private void readThread(
      SourceContext ctx, GitHubProtocol api, Round round, int number, boolean prHint, String since)
      throws Exception {
    checkEnabled();
    round.read.add(number);
    JsonNode issue = api.get((prHint ? "pulls/" : "issues/") + number);
    boolean pr = prHint || issue.hasNonNull("pull_request");
    JsonNode detail = pr ? (prHint ? issue : api.get("pulls/" + number)) : issue;
    if (pr) round.pulls.put(number, detail);
    List<ReceivedEvent> events = new ArrayList<>();
    if (detail.hasNonNull("created_at")
        && !Instant.parse(detail.path("created_at").asText()).isBefore(Instant.parse(since))) {
      ReceivedEvent event = api.issue(detail, pr);
      if (event != null) events.add(event);
    }
    List<String> kinds = pr ? List.of("conversation", "inline", "review") : List.of("conversation");
    for (String kind : kinds) {
      String path =
          kind.equals("conversation")
              ? "issues/" + number + "/comments"
              : kind.equals("inline")
                  ? "pulls/" + number + "/comments"
                  : "pulls/" + number + "/reviews";
      if (!kind.equals("review")) path += "?since=" + GitHubProtocol.encode(since);
      for (JsonNode comment : api.pages(path, null)) {
        checkEnabled();
        if (!GitHubProtocol.validComment(comment)) {
          round.skipped++;
          continue;
        }
        try {
          String at = GitHubProtocol.first(comment, "updated_at", "submitted_at", "created_at");
          if (Instant.parse(at).isBefore(Instant.parse(since))) continue;
          ReceivedEvent event =
              api.comment(comment, number, pr, kind, pr ? GitHubProtocol.actor(detail) : null);
          if (event != null) events.add(event);
        } catch (IllegalArgumentException malformed) {
          round.skipped++;
        }
      }
    }
    events.sort(Comparator.comparing(ReceivedEvent::at).thenComparing(ReceivedEvent::eventId));
    ctx.store()
        .atomic(
            () -> {
              events.forEach(e -> ingest(ctx, e, round));
              ctx.state(
                  id,
                  "thread:" + number,
                  Map.of("since", round.started.minusSeconds(5).toString(), "isPr", pr));
              ctx.engine()
                  .describeThread(
                      id + ":" + number,
                      Map.of(
                          "number",
                          number,
                          "isPr",
                          pr,
                          "title",
                          detail.path("title").asText(),
                          "label",
                          "#" + number + " " + detail.path("title").asText(),
                          "url",
                          detail.path("html_url").asText(),
                          "pollError",
                          ""));
              if (pr) observePull(ctx, number, detail);
              return null;
            });
  }

  public void claimPull(SourceContext ctx, String ticketId, int number) throws Exception {
    JsonNode pr = api().get("pulls/" + number);
    Ticket ticket = ctx.engine().ticket(ticketId);
    if (ticket == null || ticket.status != TicketStatus.OPEN)
      throw new IllegalArgumentException("Ticket not open");
    Trigger origin = ctx.engine().trigger(ticket.originTriggerId);
    boolean sourcePr =
        origin != null
            && origin.source().equals(id)
            && Boolean.TRUE.equals(origin.metadata().get("isPr"))
            && Objects.toString(origin.metadata().get("number"), "")
                .equals(Integer.toString(number));
    boolean own = ownPull(ticket, pr);
    if (!sourcePr && !own)
      throw new IllegalArgumentException(
          "PR must be origin or same-repository agent PR on ticket branch");
    for (Ticket other : ctx.engine().tickets())
      if (!other.id.equals(ticketId)
          && other.status == TicketStatus.OPEN
          && other.follows.stream()
              .anyMatch(f -> f.thread.equals(id + ":" + number) && f.closed == null))
        throw new IllegalArgumentException("PR already bound to another ticket");
    if (!closed(pr))
      ctx.engine().bindThread(ticketId, new ThreadRef(id, Integer.toString(number)), own);
    ctx.engine()
        .describeThread(
            id + ":" + number,
            Map.of("number", number, "isPr", true, "url", pr.path("html_url").asText()));
  }

  private boolean ownPull(Ticket ticket, JsonNode pr) {
    String branch = pr.path("head").path("ref").asText();
    return GitHubProtocol.actor(pr).equalsIgnoreCase(config.botLogin())
        && pr.path("head")
            .path("repo")
            .path("full_name")
            .asText()
            .equalsIgnoreCase(config.repository())
        && ((ticket.branch != null && ticket.branch.equals(branch))
            || (ticket.shortName != null
                && ticket.shortName.equals(branch.substring(branch.lastIndexOf('/') + 1))));
  }

  private void observePull(SourceContext ctx, int number, JsonNode pr) {
    if (closed(pr)) {
      String outcome = pr.path("merged").asBoolean() ? "merged" : "closed";
      String at = GitHubProtocol.first(pr, "merged_at", "closed_at");
      if (at.isBlank()) at = ctx.now().toString();
      ctx.engine().threadClosed(new ThreadRef(id, Integer.toString(number)), outcome, at);
      for (Ticket ticket : ctx.engine().tickets()) {
        if (ticket.status != TicketStatus.ENDED
            || ticket.ended == null
            || !Set.of("merged", "closed").contains(ticket.ended.outcome())) continue;
        List<Follow> ended =
            ticket.follows.stream()
                .filter(f -> f.thread.startsWith(id + ":") && f.closed != null)
                .toList();
        Follow chosen =
            ended.stream()
                .filter(f -> f.own)
                .max(Comparator.comparingLong(f -> f.closed.at()))
                .orElseGet(
                    () ->
                        ended.stream()
                            .max(Comparator.comparingLong(f -> f.closed.at()))
                            .orElse(null));
        if (chosen == null || !chosen.thread.equals(id + ":" + number)) continue;
        Trigger request = ctx.engine().trigger(ticket.originTriggerId);
        boolean chinese =
            request != null
                && request.text().matches("(?s).*[\\p{IsHan}].*")
                && !request.text().matches("(?s).*[\\p{IsHiragana}\\p{IsKatakana}].*");
        String summary =
            "PR #"
                + number
                + (chinese
                    ? (ticket.ended.outcome().equals("merged") ? " 已合并" : " 已关闭，未合并")
                    : " " + ticket.ended.outcome())
                + ": https://github.com/"
                + config.repository()
                + "/pull/"
                + number;
        for (Channel channel : ticket.channels)
          if (!channel.source().equals(id))
            ctx.engine()
                .queueReply(
                    "pr-terminal:"
                        + number
                        + ":"
                        + ticket.ended.outcome()
                        + ":"
                        + ticket.id
                        + ":"
                        + AdapterChannelSender.deliveryId(channel.toString()),
                    ticket.id,
                    channel,
                    summary);
      }
    } else {
      List<Ticket> candidates =
          ctx.engine().tickets().stream()
              .filter(t -> t.status == TicketStatus.OPEN && ownPull(t, pr))
              .toList();
      if (candidates.size() == 1) {
        Ticket ticket = candidates.getFirst();
        if (ticket.follows.stream()
                .anyMatch(f -> f.thread.equals(id + ":" + number) && f.closed != null)
            || ticket.follows.stream()
                .noneMatch(
                    f ->
                        f.closed == null
                            && f.thread.startsWith(id + ":")
                            && !f.thread.equals(id + ":" + number)))
          ctx.engine().bindThread(ticket.id, new ThreadRef(id, Integer.toString(number)), true);
      }
    }
    ctx.engine()
        .describeThread(
            id + ":" + number, Map.of("autoMerge", !closed(pr) && pr.hasNonNull("auto_merge")));
  }

  private static boolean closed(JsonNode pr) {
    return pr.path("merged").asBoolean() || pr.path("state").asText().equals("closed");
  }

  private void prCi(SourceContext ctx, GitHubProtocol api, Round round, int number, JsonNode pr)
      throws Exception {
    var snapshot = api.checks(pr.path("head").path("sha").asText());
    String fingerprint = HttpJsonTransport.JSON.writeValueAsString(snapshot);
    ObjectNode prior = state(ctx, "ci:" + number);
    if (fingerprint.equals(prior.path("fingerprint").asText())) return;
    long revision = prior.has("fingerprint") ? prior.path("revision").asLong() + 1 : 0;
    ctx.store()
        .atomic(
            () -> {
              if (prior.has("fingerprint"))
                ingest(ctx, api.ciEvent(number, null, snapshot, revision, round.started), round);
              ctx.state(
                  id, "ci:" + number, Map.of("fingerprint", fingerprint, "revision", revision));
              return null;
            });
  }

  private void branchCi(SourceContext ctx, GitHubProtocol api, Round round) throws Exception {
    String branch = api.get("").path("default_branch").asText();
    String sha = api.get("commits/" + GitHubProtocol.encode(branch)).path("sha").asText();
    var snapshot = api.checks(sha);
    ObjectNode prior = state(ctx, "branch-ci");
    boolean known = prior.path("branch").asText().equals(branch) && prior.has("failures");
    List<String> old = new ArrayList<>();
    prior.path("failures").forEach(n -> old.add(n.asText()));
    List<String> now = snapshot.failures();
    if (now.isEmpty() && snapshot.running() > 0 && known) now = old;
    boolean changed = known && !now.equals(old);
    long revision = prior.path("revision").asLong() + (changed ? 1 : 0);
    List<String> finalFailures = now;
    ctx.store()
        .atomic(
            () -> {
              if (changed)
                ingest(ctx, api.ciEvent(0, branch, snapshot, revision, round.started), round);
              ctx.state(
                  id,
                  "branch-ci",
                  Map.of("branch", branch, "failures", finalFailures, "revision", revision));
              return null;
            });
  }

  @Override
  public void installScope(SourceContext ctx) {
    ctx.engine()
        .configureSourceScope(
            id,
            new SourceScope(
                enabled(),
                Set.of("issues", "pulls", "comments", "ci", "pr-ci"),
                config.botLogin()));
  }

  @Override
  public void start(SourceContext ctx) {
    installScope(ctx);
  }

  public JsonNode createPull(String title, String body, String head, String base, boolean draft)
      throws Exception {
    if (!enabled() || !config.writesEnabled())
      throw new IllegalStateException("GitHub publishing disabled");
    return api().createPull(title, body, head, base, draft);
  }

  @Override
  public Find find(String query) {
    var number = java.util.regex.Pattern.compile("^#?([0-9]+)$").matcher(query.trim());
    if (!number.find()) {
      number =
          java.util.regex.Pattern.compile("github\\.com/[^/]+/[^/]+/(?:pull|issues)/([0-9]+)")
              .matcher(query);
      if (!number.find()) return new Find(List.of(), List.of());
    }
    return new Find(List.of(id() + ":" + number.group(1)), List.of());
  }

  public JsonNode checkIdentity() throws Exception {
    return api().identity();
  }

  public JsonNode readPull(int number) throws Exception {
    if (number < 1) throw new IllegalArgumentException("Positive PR number required");
    return api().get("pulls/" + number);
  }

  @Override
  public Object read(SourceContext ctx, String resource) throws Exception {
    if (!enabled()) throw new IllegalStateException("Source disabled");
    return resource.equals("repository") ? api().get("") : api().read(resource);
  }

  @Override
  public Object readContext(SourceContext ctx, Trigger trigger, String part, String cursor)
      throws Exception {
    if (!trigger.source().equals(id)) throw new IllegalArgumentException("Wrong source");
    if (part.equals("file") || part.equals("directory"))
      return read(ctx, "github/" + part + "/" + (cursor == null ? "" : cursor));
    if (trigger.metadata().containsKey("branch"))
      return Map.of(
          "status",
          "branch_ci",
          "messages",
          List.of(
              Map.of(
                  "id",
                  trigger.url() == null ? trigger.id() : trigger.url(),
                  "text",
                  trigger.text(),
                  "from",
                  "CI",
                  "at",
                  trigger.at(),
                  "bot",
                  true)));
    int n = Integer.parseInt(Objects.toString(trigger.metadata().get("number"), "0"));
    if (n < 1) throw new IllegalArgumentException("Missing GitHub issue anchor");
    if (!Set.of("root", "parent", "thread").contains(part))
      throw new IllegalArgumentException("Unknown GitHub context part");
    if ("inline".equals(trigger.metadata().get("kind")))
      return api()
          .reviewContext(
              n, Objects.toString(trigger.metadata().get("commentId")), part.equals("thread"));
    return api().conversationContext(n, cursor == null ? 1 : Integer.parseInt(cursor));
  }

  @Override
  public Delivery send(SourceContext ctx, OutboxMessage message) throws Exception {
    if (!enabled() || !config.writesEnabled()) return new Delivery("waiting", null);
    Map<String, String> address = message.channel().address();
    if (!config
        .repository()
        .equalsIgnoreCase(address.getOrDefault("repository", config.repository())))
      return new Delivery("gone", null);
    int number =
        Integer.parseInt(address.getOrDefault("number", address.getOrDefault("issue", "0")));
    if (number < 1) throw new IllegalArgumentException("Reply issue required");
    String review = address.get("reviewCommentId");
    if (review == null) {
      Matcher m =
          Pattern.compile("#discussion_r([1-9][0-9]*)$").matcher(address.getOrDefault("url", ""));
      if (m.find()) review = m.group(1);
    }
    try {
      return new Delivery("sent", api().send(number, review, message.text(), message.id()));
    } catch (HttpJsonTransport.ApiException error) {
      if (error.status() == 410) return new Delivery("gone", null);
      if (error.status() == 403 && api().get("issues/" + number).path("locked").asBoolean())
        return new Delivery("gone", null);
      throw error;
    }
  }

  private int number(String key) {
    try {
      return Integer.parseInt(key.substring(key.lastIndexOf(':') + 1));
    } catch (Exception ignored) {
      return 0;
    }
  }

  private String initialThreadTime(SourceContext ctx, int number, Instant fallback) {
    return ctx.store().list("trigger", Trigger.class).stream()
        .filter(
            t ->
                t.source().equals(id)
                    && t.threads().stream().anyMatch(r -> number(r.key()) == number))
        .map(Trigger::at)
        .min(String::compareTo)
        .orElse(fallback.toString());
  }
}
