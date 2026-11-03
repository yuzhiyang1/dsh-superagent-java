package io.github.dsh.sources;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsh.adapters.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/** GitHub 的线协议和规范化读资源；不接触 Policy，也不会直接创建工单。 */
public final class GitHubProtocol {
  private static final Map<String, String> HEADERS =
      Map.of("Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28");
  private static final Set<String> FAILING =
      Set.of("failure", "timed_out", "cancelled", "action_required", "startup_failure", "error");
  private final HttpJsonTransport http;
  private final String repository, source, bot;
  private final int maxPages;
  private final String cli;

  public GitHubProtocol(
      URI base, String token, String repository, String source, String bot, int maxPages) {
    this(base, token, repository, source, bot, maxPages, "");
  }

  public GitHubProtocol(
      URI base,
      String token,
      String repository,
      String source,
      String bot,
      int maxPages,
      String cli) {
    if (repository == null
        || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
        || repository.contains(".."))
      throw new IllegalArgumentException("owner/repository required");
    this.http = new HttpJsonTransport(base, token);
    this.repository = repository;
    this.source = source;
    this.bot = bot;
    this.maxPages = maxPages;
    this.cli = cli == null ? "" : cli;
  }

  public static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  public JsonNode get(String path) throws Exception {
    return request("GET", repo(path), null, HEADERS).body();
  }

  public JsonNode identity() throws Exception {
    return request("GET", "user", null, HEADERS).body();
  }

  public HttpJsonTransport.Response detail(String path, Map<String, String> extra)
      throws Exception {
    Map<String, String> headers = new HashMap<>(HEADERS);
    headers.putAll(extra);
    return request("GET", path.startsWith("notifications?") ? path : repo(path), null, headers);
  }

  private HttpJsonTransport.Response request(
      String method, String path, Object body, Map<String, String> headers) throws Exception {
    if (cli.isBlank()) return http.requestDetailed(method, path, body, headers);
    List<String> args = new ArrayList<>(List.of("api", path, "--include", "--method", method));
    headers.forEach((k, v) -> args.addAll(List.of("-H", k + ": " + v)));
    java.nio.file.Path input = null;
    try {
      if (body != null) {
        input = java.nio.file.Files.createTempFile("dsh-github-body-", ".json");
        java.nio.file.Files.writeString(input, HttpJsonTransport.JSON.writeValueAsString(body));
        args.addAll(List.of("--input", input.toString()));
      }
      var result =
          SourceCommand.run(cli, args, null, 2 * 1024 * 1024, java.time.Duration.ofSeconds(60));
      String output = SourceCommand.text(result.output());
      Matcher split = Pattern.compile("\\r?\\n\\r?\\n").matcher(output);
      if (!split.find()) {
        Matcher error =
            Pattern.compile("HTTP[ /]([0-9]{3})").matcher(SourceCommand.text(result.error()));
        if (error.find())
          throw new HttpJsonTransport.ApiException(Integer.parseInt(error.group(1)));
        throw new IOException("GitHub CLI response missing headers");
      }
      String head = output.substring(0, split.start()), content = output.substring(split.end());
      String[] lines = head.split("\\r?\\n");
      Matcher status = Pattern.compile("HTTP/[^ ]+ +([0-9]{3})").matcher(lines[0]);
      if (!status.find()) throw new IOException("GitHub CLI status unavailable");
      int code = Integer.parseInt(status.group(1));
      if (code != 304 && (code < 200 || code >= 300))
        throw new HttpJsonTransport.ApiException(code);
      if (result.exit() != 0 && code != 304) throw new IOException("GitHub CLI failed");
      Map<String, List<String>> fields = new LinkedHashMap<>();
      for (int i = 1; i < lines.length; i++) {
        int colon = lines[i].indexOf(':');
        if (colon > 0)
          fields
              .computeIfAbsent(
                  lines[i].substring(0, colon).toLowerCase(Locale.ROOT),
                  unused -> new ArrayList<>())
              .add(lines[i].substring(colon + 1).trim());
      }
      return new HttpJsonTransport.Response(
          code,
          fields,
          code == 304 || content.isBlank()
              ? HttpJsonTransport.JSON.nullNode()
              : HttpJsonTransport.JSON.readTree(content));
    } finally {
      if (input != null) java.nio.file.Files.deleteIfExists(input);
    }
  }

  private String repo(String path) {
    return "repos/" + repository + (path.isEmpty() ? "" : "/" + path);
  }

  public List<JsonNode> pages(String path, String arrayField) throws Exception {
    List<JsonNode> result = new ArrayList<>();
    for (int page = 1; page <= maxPages; page++) {
      JsonNode response =
          path.startsWith("notifications?")
              ? detail(path + "&page=" + page, Map.of()).body()
              : get(path + (path.contains("?") ? "&" : "?") + "per_page=100&page=" + page);
      JsonNode items = arrayField == null ? response : response.path(arrayField);
      if (!items.isArray()) throw new IOException("GitHub list response invalid");
      items.forEach(result::add);
      if (items.size() < 100) return result;
    }
    throw new IOException("GitHub page limit reached; checkpoint unchanged");
  }

  public record Ci(
      String head, List<Map<String, String>> checks, List<Map<String, String>> statuses) {
    public Ci {
      checks =
          checks.stream()
              .map(m -> Collections.unmodifiableMap(new TreeMap<>(m)))
              .sorted(Comparator.comparing(Object::toString))
              .toList();
      statuses =
          statuses.stream()
              .map(m -> Collections.unmodifiableMap(new TreeMap<>(m)))
              .sorted(Comparator.comparing(Object::toString))
              .toList();
    }

    public List<String> failures() {
      List<String> names = new ArrayList<>();
      checks.stream()
          .filter(c -> FAILING.contains(c.get("conclusion")))
          .forEach(c -> names.add(c.get("name")));
      statuses.stream()
          .filter(c -> FAILING.contains(c.get("state")))
          .forEach(c -> names.add(c.get("context")));
      return names.stream().sorted().toList();
    }

    public int running() {
      return (int)
          (checks.stream().filter(c -> !"completed".equals(c.get("status"))).count()
              + statuses.stream().filter(c -> "pending".equals(c.get("state"))).count());
    }

    public String summary(String location) {
      return "CI "
          + (failures().isEmpty()
              ? running() > 0 ? "running" : "passed"
              : "failed: " + String.join(", ", failures()))
          + " on "
          + location
          + " at "
          + head
          + "; running="
          + running();
    }
  }

  public Ci checks(String sha) throws Exception {
    if (!sha.matches("[A-Za-z0-9_-]{7,64}"))
      throw new IllegalArgumentException("Invalid commit sha");
    List<Map<String, String>> checks = new ArrayList<>();
    for (JsonNode run : pages("commits/" + sha + "/check-runs", "check_runs"))
      if (run.path("name").isTextual() && run.path("status").isTextual())
        checks.add(
            Map.of(
                "name",
                run.path("name").asText(),
                "status",
                run.path("status").asText(),
                "conclusion",
                run.path("conclusion").asText("")));
    checks.sort(Comparator.comparing(Object::toString));
    Map<String, String> latest = new TreeMap<>();
    for (JsonNode status : pages("commits/" + sha + "/statuses", null))
      if (status.path("context").isTextual() && status.path("state").isTextual())
        latest.putIfAbsent(status.path("context").asText(), status.path("state").asText());
    return new Ci(
        sha,
        checks,
        latest.entrySet().stream()
            .map(e -> Map.of("context", e.getKey(), "state", e.getValue()))
            .toList());
  }

  public ReceivedEvent issue(JsonNode issue, boolean pr) {
    String actor = actor(issue);
    if (actor.equalsIgnoreCase(bot)) return null;
    int number = issue.path("number").asInt();
    if (number < 1) throw new IllegalArgumentException("Missing issue number");
    String body = issue.path("body").asText("");
    String kind = pr ? "pull" : "issue";
    return event(
        source + ":issue:" + number,
        number,
        actor,
        isBot(issue),
        GitHubAdapter.mentions(body, bot),
        issue.path("title").asText() + "\n\n" + body,
        requiredTime(issue, "created_at"),
        Map.of(
            "number",
            number,
            "isPr",
            pr,
            "kind",
            kind,
            "prAuthor",
            pr ? actor : "",
            "head",
            pr ? "refs/pull/" + number + "/head" : ""),
        issue.path("html_url").asText(),
        null,
        null);
  }

  public ReceivedEvent comment(JsonNode comment, int number, boolean pr, String kind) {
    String actor = actor(comment);
    if (actor.equalsIgnoreCase(bot)) return null;
    String id = required(comment, "id");
    String revision = first(comment, "updated_at", "submitted_at", "created_at");
    String body = comment.path("body").asText("");
    String text = body;
    if ("review".equals(kind)
        && comment.hasNonNull("state")
        && !comment.path("state").asText().equals("COMMENTED"))
      text =
          comment.path("state").asText()
              + " review of commit "
              + comment.path("commit_id").asText("unknown")
              + (body.isBlank() ? "" : "\n\n" + body);
    if ("inline".equals(kind) && comment.hasNonNull("path")) {
      String[] lines = comment.path("diff_hunk").asText().split("\n");
      String line = comment.path("line").asText(comment.path("original_line").asText());
      text =
          comment.path("path").asText()
              + (line.isBlank() ? "" : ":" + line)
              + "\n"
              + String.join(
                  "\n", Arrays.copyOfRange(lines, Math.max(0, lines.length - 3), lines.length))
              + (body.isBlank() ? "" : "\n\n" + body);
    }
    if (text.isBlank()) return null;
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("number", number);
    meta.put("isPr", pr);
    meta.put("kind", kind);
    meta.put("commentId", id);
    if (pr) meta.put("head", "refs/pull/" + number + "/head");
    if (comment.hasNonNull("in_reply_to_id"))
      meta.put("inReplyTo", comment.path("in_reply_to_id").asText());
    return event(
        source + ":comment:" + kind + ":" + number + ":" + id + ":" + revision,
        number,
        actor,
        isBot(comment),
        GitHubAdapter.mentions(body, bot),
        text,
        Instant.parse(revision),
        meta,
        comment.path("html_url").asText(),
        "inline".equals(kind) ? id : null,
        comment.path("in_reply_to_id").asText(null));
  }

  public ReceivedEvent comment(
      JsonNode comment, int number, boolean pr, String kind, String pullAuthor) {
    ReceivedEvent event = comment(comment, number, pr, kind);
    if (event == null || pullAuthor == null || pullAuthor.isBlank()) return event;
    Map<String, Object> metadata = new LinkedHashMap<>(event.metadata());
    metadata.put("prAuthor", pullAuthor);
    return new ReceivedEvent(
        event.sourceId(),
        event.eventId(),
        event.threadId(),
        event.actor(),
        event.bot(),
        event.addressed(),
        event.text(),
        event.at(),
        event.replyAddress(),
        metadata,
        event.url(),
        event.replyTo());
  }

  private ReceivedEvent event(
      String id,
      int number,
      String actor,
      boolean botActor,
      boolean addressed,
      String text,
      Instant at,
      Map<String, Object> metadata,
      String url,
      String review,
      String replyTo) {
    Map<String, String> address =
        new LinkedHashMap<>(
            Map.of(
                "repository",
                repository,
                "issue",
                Integer.toString(number),
                "number",
                Integer.toString(number)));
    if (url != null && !url.isBlank()) address.put("url", url);
    if (review != null) address.put("reviewCommentId", review);
    return new ReceivedEvent(
        source,
        id,
        source + ":" + number,
        actor,
        botActor,
        addressed,
        text,
        at,
        address,
        metadata,
        url,
        replyTo);
  }

  public ReceivedEvent ciEvent(int number, String branch, Ci snapshot, long revision, Instant at) {
    boolean branchCi = branch != null;
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("ci", true);
    metadata.put("isPr", !branchCi);
    if (branchCi) metadata.put("branch", branch);
    else metadata.put("number", number);
    String thread = branchCi ? "ci:" + branch : Integer.toString(number);
    return new ReceivedEvent(
        source,
        source + ":ci:" + thread + ":" + revision,
        source + ":" + thread,
        "",
        true,
        false,
        snapshot.summary(branchCi ? branch : "PR #" + number),
        at,
        branchCi ? Map.of() : Map.of("repository", repository, "issue", Integer.toString(number)),
        metadata,
        "https://github.com/"
            + repository
            + "/"
            + (branchCi ? "commit/" + snapshot.head() : "pull/" + number),
        null);
  }

  public Object read(String resource) throws Exception {
    Matcher commit = Pattern.compile("github/commits/([0-9a-fA-F]{7,64})/checks").matcher(resource);
    if (commit.matches()) return checks(commit.group(1));
    if (resource.startsWith("github/file/") || resource.startsWith("github/directory/")) {
      boolean file = resource.startsWith("github/file/");
      String path = resource.substring(file ? 12 : 17);
      List<String> parts = Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList();
      if (parts.stream().anyMatch(p -> p.startsWith(".") || p.equals("node_modules")))
        throw new IllegalArgumentException("Hidden/traversal repository paths forbidden");
      JsonNode value =
          get("contents/" + String.join("/", parts.stream().map(GitHubProtocol::encode).toList()));
      if (file) {
        if (!"file".equals(value.path("type").asText())
            || !"base64".equals(value.path("encoding").asText()))
          throw new IOException("File unavailable or too large");
        return new String(
            Base64.getMimeDecoder().decode(value.path("content").asText()), StandardCharsets.UTF_8);
      }
      if (!value.isArray()) throw new IOException("Not a directory");
      List<String> names = new ArrayList<>();
      value.forEach(
          v -> {
            String n = v.path("name").asText();
            if (!n.startsWith(".") && !n.equals("node_modules")) names.add(n);
          });
      return names.stream().sorted().toList();
    }
    Matcher match =
        Pattern.compile(
                "github/(issues|pulls)/([1-9][0-9]*)(?:/(comments|reviews|checks|thread)(?:/([1-9][0-9]*))?)?")
            .matcher(resource);
    if (!match.matches()) throw new IllegalArgumentException("Unknown GitHub resource");
    String kind = match.group(1),
        number = match.group(2),
        section = match.group(3),
        page = match.group(4) == null ? "1" : match.group(4);
    if (kind.equals("issues") && section != null && !section.equals("comments"))
      throw new IllegalArgumentException("Review/check resource requires PR");
    if (section == null) {
      JsonNode item = get(kind + "/" + number);
      Map<String, Object> result = new LinkedHashMap<>(message(item));
      result.put("id", number);
      result.put("text", item.path("body").asText(""));
      for (String key : List.of("number", "title", "state"))
        if (item.has(key)) result.put(key, item.get(key));
      for (String key : List.of("merged", "draft"))
        if (item.path(key).asBoolean()) result.put(key, true);
      for (String key : List.of("head", "base"))
        if (item.has(key)) result.put(key, item.path(key).path("ref").asText());
      if (item.path("labels").isArray() && !item.path("labels").isEmpty()) {
        List<String> labels = new ArrayList<>();
        item.path("labels").forEach(l -> labels.add(l.path("name").asText()));
        result.put("labels", labels);
      }
      return result;
    }
    if (section.equals("checks") && match.group(4) != null)
      throw new IllegalArgumentException("Checks do not take a page number");
    if (section.equals("checks"))
      return checks(get("pulls/" + number).path("head").path("sha").asText());
    if (section.equals("thread")) {
      if (match.group(4) == null) throw new IllegalArgumentException("Comment id required");
      List<JsonNode> comments = pages("pulls/" + number + "/comments", null);
      String root =
          comments.stream()
              .filter(c -> c.path("id").asText().equals(page))
              .findFirst()
              .map(c -> c.path("in_reply_to_id").asText(page))
              .orElseThrow(() -> new IllegalArgumentException("Unknown comment"));
      List<JsonNode> thread =
          comments.stream()
              .filter(
                  c ->
                      c.path("id").asText().equals(root)
                          || c.path("in_reply_to_id").asText().equals(root))
              .sorted(Comparator.comparing(c -> c.path("created_at").asText()))
              .toList();
      List<Map<String, Object>> messages = new ArrayList<>();
      for (int i = 0; i < thread.size(); i++) {
        JsonNode comment = thread.get(i);
        Map<String, Object> view = new LinkedHashMap<>(message(comment));
        if (i == 0) {
          String[] lines = comment.path("diff_hunk").asText().split("\n");
          String hunk =
              String.join(
                  "\n", Arrays.copyOfRange(lines, Math.max(0, lines.length - 3), lines.length));
          view.put(
              "text",
              comment.path("path").asText()
                  + ":"
                  + comment.path("line").asText(comment.path("original_line").asText())
                  + "\n"
                  + hunk
                  + "\n\n"
                  + comment.path("body").asText());
        } else view.put("reply_to", root);
        messages.add(view);
      }
      return Map.of("messages", messages);
    }
    // 与上游资源契约一致：pulls/<n>/comments 是 inline reviews，普通 PR 讨论用 issues/<n>/comments。
    String path = kind + "/" + number + "/" + section;
    JsonNode items = get(path + "?per_page=20&page=" + page);
    if (!items.isArray()) throw new IOException("Invalid comments page");
    List<Map<String, Object>> messages = new ArrayList<>();
    int skipped = 0;
    for (JsonNode item : items) {
      if (!validComment(item)) {
        skipped++;
        continue;
      }
      messages.add(message(item));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("messages", messages);
    if (skipped > 0) result.put("skipped", skipped);
    if (items.size() == 20)
      result.put(
          "next_resource",
          "github/" + kind + "/" + number + "/" + section + "/" + (Integer.parseInt(page) + 1));
    return result;
  }

  /** Eval 上下文使用网页 URL 作为 message id，才能与 Trigger.url 精确定位同一条消息。 */
  public Map<String, Object> conversationContext(int number, int page) throws Exception {
    if (number < 1 || page < 1) throw new IllegalArgumentException("Positive issue/page required");
    List<Map<String, Object>> messages = new ArrayList<>();
    if (page == 1) {
      JsonNode issue = get("issues/" + number);
      Map<String, Object> root = contextMessage(issue);
      root.put("text", issue.path("title").asText() + "\n\n" + issue.path("body").asText(""));
      messages.add(root);
    }
    JsonNode items = get("issues/" + number + "/comments?per_page=100&page=" + page);
    if (!items.isArray()) throw new IOException("Invalid conversation page");
    int skipped = 0;
    for (JsonNode item : items) {
      if (!validComment(item)) {
        skipped++;
        continue;
      }
      messages.add(contextMessage(item));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("messages", messages);
    if (items.size() == 100) result.put("next", Integer.toString(page + 1));
    if (skipped > 0) result.put("skipped", skipped);
    return result;
  }

  public Map<String, Object> reviewContext(int number, String commentId, boolean full)
      throws Exception {
    JsonNode body =
        HttpJsonTransport.JSON.valueToTree(read("github/pulls/" + number + "/thread/" + commentId));
    List<Map<String, Object>> messages = new ArrayList<>();
    Map<String, String> ids = new HashMap<>();
    for (JsonNode item : body.path("messages"))
      ids.put(item.path("id").asText(), item.path("url").asText(item.path("id").asText()));
    for (JsonNode item : body.path("messages")) {
      Map<String, Object> message =
          HttpJsonTransport.JSON.convertValue(
              item, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      message.put("id", ids.get(item.path("id").asText()));
      if (message.containsKey("reply_to"))
        message.put(
            "reply_to",
            ids.getOrDefault(
                message.get("reply_to").toString(), message.get("reply_to").toString()));
      messages.add(message);
      if (!full) break;
    }
    return Map.of("status", "review_thread", "messages", messages);
  }

  private static Map<String, Object> contextMessage(JsonNode item) {
    Map<String, Object> result = message(item);
    result.put("id", item.path("html_url").asText(item.path("id").asText()));
    return result;
  }

  static boolean validComment(JsonNode item) {
    if (!item.path("id").isIntegralNumber() || !item.has("user")) return false;
    JsonNode user = item.get("user");
    if (!user.isNull() && (!user.isObject() || !user.path("login").isTextual())) return false;
    for (String key :
        List.of(
            "body",
            "html_url",
            "created_at",
            "updated_at",
            "submitted_at",
            "state",
            "commit_id",
            "path",
            "diff_hunk")) if (item.hasNonNull(key) && !item.path(key).isTextual()) return false;
    return true;
  }

  public JsonNode createPull(String title, String body, String head, String base, boolean draft)
      throws Exception {
    if (title == null
        || title.isBlank()
        || title.length() > 256
        || body == null
        || body.length() > 65536) throw new IllegalArgumentException("Invalid PR title/body");
    for (String ref : List.of(head, base))
      if (ref.isBlank() || ref.contains("..") || !ref.matches("[A-Za-z0-9_./-]+"))
        throw new IllegalArgumentException("Invalid branch ref");
    String owner = repository.substring(0, repository.indexOf('/'));
    for (JsonNode pr :
        pages(
            "pulls?state=open&head=" + encode(owner + ":" + head) + "&base=" + encode(base), null))
      if (pr.path("head").path("ref").asText().equals(head)
          && pr.path("base").path("ref").asText().equals(base)
          && actor(pr).equalsIgnoreCase(bot)) return pr;
    return request(
            "POST",
            repo("pulls"),
            Map.of("title", title, "body", body, "head", head, "base", base, "draft", draft),
            HEADERS)
        .body();
  }

  public String send(int number, String reviewCommentId, String text, String stableId)
      throws Exception {
    String root = null;
    if (reviewCommentId != null && !reviewCommentId.isBlank()) {
      if (!reviewCommentId.matches("[1-9][0-9]*"))
        throw new IllegalArgumentException("Review comment id invalid");
      try {
        JsonNode comment = get("pulls/comments/" + reviewCommentId);
        root = comment.path("in_reply_to_id").asText(comment.path("id").asText());
      } catch (HttpJsonTransport.ApiException e) {
        if (e.status() != 404 && e.status() != 410) throw e;
      }
    }
    String path = root == null ? "issues/" + number + "/comments" : "pulls/" + number + "/comments";
    String marker = "<!-- dsh-superagent:" + AdapterChannelSender.deliveryId(stableId) + " -->";
    for (JsonNode existing : pages(path, null))
      if (actor(existing).equalsIgnoreCase(bot)
          && existing.path("body").asText().contains(marker)
          && (root == null || existing.path("in_reply_to_id").asText().equals(root)))
        return required(existing, "id");
    String target = root == null ? path : path + "/" + root + "/replies";
    return required(
        request("POST", repo(target), Map.of("body", text + "\n\n" + marker), HEADERS).body(),
        "id");
  }

  public static Map<String, Object> message(JsonNode item) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", item.path("id").asText(item.path("number").asText()));
    m.put("from", actor(item));
    m.put("at", first(item, "created_at", "submitted_at", "updated_at"));
    String state = item.path("state").asText();
    m.put(
        "text",
        (Set.of("APPROVED", "CHANGES_REQUESTED", "DISMISSED", "PENDING").contains(state)
                ? state + "\n"
                : "")
            + item.path("body").asText(""));
    if (isBot(item)) m.put("bot", true);
    for (String key : List.of("path", "line", "html_url", "in_reply_to_id"))
      if (item.hasNonNull(key))
        m.put(
            key.equals("html_url") ? "url" : key.equals("in_reply_to_id") ? "reply_to" : key,
            item.get(key));
    return m;
  }

  static String actor(JsonNode item) {
    return item.path("user").path("login").asText("ghost");
  }

  static boolean isBot(JsonNode item) {
    return actor(item).endsWith("[bot]")
        || !item.path("user").path("type").asText("User").equals("User");
  }

  static String first(JsonNode item, String... keys) {
    for (String key : keys)
      if (item.hasNonNull(key) && !item.path(key).asText().isBlank())
        return item.path(key).asText();
    return "";
  }

  static Instant requiredTime(JsonNode item, String field) {
    return Instant.parse(required(item, field));
  }

  static String required(JsonNode item, String key) {
    String value = item.path(key).asText();
    if (value.isBlank()) throw new IllegalArgumentException("Missing API field " + key);
    return value;
  }
}
