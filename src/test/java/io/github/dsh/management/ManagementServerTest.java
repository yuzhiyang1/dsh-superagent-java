package io.github.dsh.management;

import static io.github.dsh.config.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsh.config.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ManagementServerTest {
  @TempDir Path directory;
  SqliteStore store;
  Engine engine;
  VersionedSettings settings;
  PromptService prompts;
  ManagementServer server;
  HttpClient client = HttpClient.newHttpClient();
  ManagementHost host =
      new ManagementHost() {
        public JsonNode setup() {
          return object(
              "problem",
              null,
              "feishuProblem",
              null,
              "accounts",
              object("repository", "acme/widgets", "agent", "fixture-agent"));
        }

        public JsonNode health() {
          return object("runtime", object("state", "ready", "detail", "fixture"));
        }

        public List<Map<String, String>> environment(String ticket) {
          return List.of(
              Map.of("key", "DSH_SUPERAGENT_TICKET", "value", ticket == null ? "" : ticket));
        }

        public JsonNode synchronizePolicy(String source) {
          return object(
              "allowed", List.of(object("id", "bob")), "bots", List.of(), "admission", object());
        }

        public void checkSetup() {}
      };

  @BeforeEach
  void start() throws Exception {
    store = new SqliteStore(directory.resolve("state.db"));
    engine = new Engine(store);
    settings = new VersionedSettings(store, engine, directory, host);
    prompts = new PromptService(store, settings, host);
    settings.save(
        "policy",
        object(
            "rules",
            List.of(),
            "sources",
            object(
                "github",
                object(
                    "allowed",
                    List.of(object("id", "Alice")),
                    "bots",
                    List.of(),
                    "admission",
                    object()))),
        0,
        "panel");
    server = new ManagementServer(engine, store, settings, prompts, host, 0);
    server.start();
  }

  @AfterEach
  void close() {
    server.close();
    store.close();
  }

  HttpResponse<String> call(String path, String method, JsonNode body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/orchestrator/api/" + path))
            .header("x-orchestrator-request", "1")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body.toString()))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode get(String path) throws Exception {
    var r = call(path, "GET", null);
    assertEquals(200, r.statusCode(), r.body());
    return parse(r.body());
  }

  Trigger trigger(String id, String thread, String actor) {
    return new Trigger(
        id,
        "github",
        List.of(new ThreadRef("github", thread)),
        actor,
        true,
        true,
        true,
        "issues",
        Instant.now().toString(),
        "Fix a typo in " + id,
        new Channel("github", thread, Map.of("repository", "acme/widgets", "number", thread)));
  }

  String accept(String triggerId, String thread) {
    engine.receive(trigger(triggerId, thread, "alice"));
    Evaluation e =
        engine.evaluateDue().stream()
            .filter(v -> v.triggerIds().contains(triggerId))
            .findFirst()
            .orElseThrow();
    return engine
        .decide(e.id(), Outcome.accept("修复标题 " + thread, "Fix title", Set.of()))
        .ticketId();
  }

  @Test
  void enforcesOriginCsrfHostAndBoundedBodies() throws Exception {
    var uri = URI.create("http://127.0.0.1:" + server.port() + "/orchestrator/api/state");
    assertEquals(
        403,
        client
            .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        403,
        client
            .send(
                HttpRequest.newBuilder(uri)
                    .header("x-orchestrator-request", "1")
                    .header("Origin", "https://evil.example")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    var large =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + server.port() + "/orchestrator/api/executor/pause"))
                .header("x-orchestrator-request", "1")
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(128 * 1024 + 1)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, large.statusCode());
    assertEquals(400, call("executor/pause", "POST", node("not an object")).statusCode());
    assertEquals(404, call("unknown", "GET", null).statusCode());
    var staticPage =
        client.send(
            HttpRequest.newBuilder(URI.create(server.url())).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, staticPage.statusCode());
    assertTrue(staticPage.body().contains("app.js"));
    assertTrue(
        staticPage
            .headers()
            .firstValue("Content-Security-Policy")
            .orElseThrow()
            .contains("frame-ancestors 'none'"));
  }

  @Test
  void savesIndependentBlocksConflictsHistoriesAndRestores() throws Exception {
    JsonNode initial = get("settings");
    assertTrue(initial.path("sources").isArray());
    int rev = settings.revision("eval");
    var save =
        call("settings/eval", "PUT", object("value", object("mode", "auto"), "revision", rev));
    assertEquals(200, save.statusCode(), save.body());
    int next = parse(save.body()).path("revision").asInt();
    assertEquals(120, settings.value("eval").path("timeoutSeconds").asInt());
    assertEquals(
        409,
        call("settings/eval", "PUT", object("value", object("mode", "off"), "revision", rev))
            .statusCode());
    assertEquals(200, call("executor/pause", "POST", object("paused", true)).statusCode());
    assertTrue(engine.processing().paused());
    assertEquals(next, settings.revision("eval"));
    assertEquals(
        400,
        call("settings/executor", "PUT", object("value", object("paused", true), "revision", 0))
            .statusCode());
    JsonNode history = get("settings/history?block=eval");
    assertEquals(1, history.path("items").size());
    assertEquals(
        200,
        call("settings/eval", "PUT", object("value", object("mode", "off"), "revision", next))
            .statusCode());
    assertEquals(
        200,
        call(
                "settings/eval/restore",
                "POST",
                object(
                    "revision",
                    history.path("items").get(0).path("revision"),
                    "baseRevision",
                    settings.revision("eval")))
            .statusCode());
    assertEquals("auto", settings.value("eval").path("mode").asText());
  }

  @Test
  void modelToolCannotEscalateEnvironmentOrAccounts() {
    ObjectNode executor = (ObjectNode) settings.value("executor").deepCopy();
    executor.put("allowShell", true);
    assertThrows(ApiException.class, () -> settings.save("executor", executor, 0, "tool"));
    ObjectNode source = (ObjectNode) settings.value("source:github").deepCopy();
    ((ObjectNode) source.path("config")).put("tokenEnvironment", "PERSONAL_TOKEN");
    assertThrows(ApiException.class, () -> settings.save("source:github", source, 0, "tool"));
    ObjectNode safe = (ObjectNode) settings.value("executor").deepCopy();
    safe.put("maxWorkers", 4);
    settings.save("executor", safe, 0, "tool");
    assertEquals(4, settings.value("executor").path("maxWorkers").asInt());
  }

  @Test
  void sourceDisableDoesNotCorruptPolicyVersionAndGitHubIdentitiesIgnoreCase() {
    String id = accept("t1", "12");
    assertNotNull(id);
    ObjectNode source = (ObjectNode) settings.value("source:github").deepCopy();
    source.put("enabled", false);
    assertDoesNotThrow(() -> settings.save("source:github", source, 0, "panel"));
    assertEquals(settings.policyVersion(), engine.policy().version());
  }

  @Test
  void promptVersionsPreviewTemplatesSkillsAndOptimisticConflicts() throws Exception {
    JsonNode view = get("prompts");
    assertEquals(15, view.path("parts").size());
    assertTrue(prompts.compose("orchestrator", List.of()).contains("## Policy"));
    ObjectNode c = prompts.defaults();
    c.put("communication", "Use clear words.");
    ((ObjectNode) c.path("skills")).put("identities", "Alice is a maintainer.");
    assertEquals(200, call("prompts", "PUT", object("config", c, "baseRevision", 0)).statusCode());
    assertEquals(409, call("prompts", "PUT", object("config", c, "baseRevision", 0)).statusCode());
    assertEquals(2, get("prompts").path("revisions").size());
    assertEquals(0, get("prompts?revision=0").path("current").path("revision").asInt());
    JsonNode preview = parse(call("prompts/preview", "POST", c).body());
    assertTrue(preview.path("orchestrator").asText().contains("Use clear words."));
    assertEquals("Alice is a maintainer.", preview.path("skills").path("identities").asText());
    ObjectNode bad = c.deepCopy();
    bad.set("overrides", object("runtime/environment", object("text", "Missing variable")));
    assertEquals(400, call("prompts/preview", "POST", bad).statusCode());
    bad = c.deepCopy();
    ((ObjectNode) bad.path("skills")).put("github", "reserved");
    assertEquals(400, call("prompts/preview", "POST", bad).statusCode());
  }

  @Test
  void promptDefaultDriftAndIdenticalOverrideNormalization() {
    ObjectNode c = prompts.defaults();
    JsonNode p = prompts.view(null, 0).path("parts").get(0);
    c.set(
        "overrides",
        object(
            p.path("key").asText(),
            object("text", p.path("text").asText(), "base", p.path("base").asText())));
    assertFalse(prompts.validate(c).has("overrides"));
    ((ObjectNode) c.path("overrides").path(p.path("key").asText()))
        .put("text", "Changed text")
        .put("base", "previous-version");
    assertEquals(1, prompts.defaultChanged(prompts.validate(c)).size());
  }

  @Test
  void allReadPagesUseDomainRecordsAndCorrectLoad() throws Exception {
    String first = accept("t1", "12"), second = accept("t2", "13");
    engine.assignmentStarted(first, engine.ticket(first).latestAssignment().id);
    assertEquals(1, get("state").path("load").path("running").asInt());
    assertEquals(1, get("state").path("load").path("queued").asInt());
    for (String path :
        List.of(
            "state",
            "overview",
            "sources",
            "settings",
            "threads?filter=all",
            "threads/github%3A12",
            "tickets",
            "tickets/" + first,
            "decisions",
            "models",
            "policy/history",
            "prompts",
            "deployment",
            "resources?resource=ticket/" + first)) assertNotNull(get(path));
    assertEquals(1, get("search?q=%2312").path("threads").size());
    assertEquals(1, get("threads?filter=all&limit=1").path("items").size());
    assertNotNull(get("threads?filter=all&limit=1").get("next_offset"));
  }

  @Test
  void ticketRetryAnswerStopAndOwnedFollowupRequeueMutateRealEngine() throws Exception {
    String id = accept("t1", "12");
    engine.assignmentStarted(id, engine.ticket(id).latestAssignment().id);
    engine.report(
        id,
        engine.ticket(id).latestAssignment().id,
        new Report(ReportKind.BLOCKED, "Need clarification", "Which wording?"));
    assertEquals(
        200,
        call("tickets/" + id + "/answer", "POST", object("text", "Use the existing label."))
            .statusCode());
    assertTrue(engine.ticket(id).inputs.size() > 1);
    engine.receive(trigger("follow", "12", "Alice"));
    assertThrows(IllegalStateException.class, () -> engine.reevaluate("follow"));
    assertEquals(200, call("triggers/follow/requeue", "POST", null).statusCode());
    assertTrue(engine.triggerDisposition("follow").requeued);
    assertEquals(200, call("tickets/" + id + "/stop", "POST", null).statusCode());
    assertTrue(engine.ticket(id).stopped);
    assertEquals(400, call("tickets/" + id + "/retry", "POST", null).statusCode());
    assertEquals(404, call("tickets/missing/stop", "POST", null).statusCode());
  }

  @Test
  void workerResourcesRejectOtherTicketAndSharedThreadMetadataLeaks() {
    String a = accept("t1", "12"), b = accept("t2", "13");
    engine.followThread(b, new ThreadRef("github", "12"), true);
    ResourceService resources = server.resources();
    assertThrows(ApiException.class, () -> resources.read("settings", 0, 4000, a));
    assertThrows(ApiException.class, () -> resources.read("ticket/" + b, 0, 4000, a));
    assertThrows(ApiException.class, () -> resources.read("trigger/t2", 0, 4000, a));
    String own = resources.read("ticket/" + a, 0, 4000, a).toString();
    assertFalse(own.contains(b), own);
    String shared = resources.read("thread/github:12", 0, 4000, b).toString();
    assertFalse(shared.contains(a), shared);
    assertThrows(ApiException.class, () -> resources.read("prompts/current", 0, 4000, a));
    prompts.recordUse("worker:" + a, 0);
    assertNotNull(resources.read("prompts/0", 0, 4000, a));
    assertThrows(ApiException.class, () -> resources.read("github/issues/12", 0, 4000, a));
    assertThrows(ApiException.class, () -> resources.read("trigger/t1", 0, 8001, a));
  }

  @Test
  void genericChannelLinksKeepDomainAddressAndRejectExecutableUrls() {
    String id = accept("channel-origin", "12");
    engine.assignmentStarted(id, engine.ticket(id).latestAssignment().id);
    engine.report(
        id,
        engine.ticket(id).latestAssignment().id,
        new Report(ReportKind.BLOCKED, "Need wording", "Which spelling?"));
    ManagementHost links =
        new ManagementHost() {
          public JsonNode channelLink(Channel channel) {
            return object(
                "url",
                "https://queue.example/conversations/12",
                "label",
                object("zh", "原始对话", "en", "Original conversation"));
          }
        };
    ManagementViews view = new ManagementViews(engine, store, settings, links);
    JsonNode detail = view.ticketDetail(id), channel = detail.path("channels").get(0);
    assertEquals("github", channel.path("source").asText());
    assertEquals("12", channel.path("thread").asText());
    assertEquals("acme/widgets", channel.path("address").path("repository").asText());
    assertEquals("https://queue.example/conversations/12", channel.path("url").asText());
    assertEquals("Original conversation", channel.path("label").path("en").asText());
    assertEquals(channel, detail.path("reply"));
    ManagementHost malicious =
        new ManagementHost() {
          public JsonNode channelLink(Channel channel) {
            return object("url", "javascript:alert(1)", "label", "Untrusted");
          }
        };
    assertEquals(
        "",
        new ManagementViews(engine, store, settings, malicious)
            .ticketDetail(id)
            .path("channels")
            .get(0)
            .path("url")
            .asText());
  }

  @Test
  void overviewCountsJudgedTriggersAndShowsPendingReplyFailures() {
    engine.receive(trigger("batch-a", "12", "alice"));
    engine.receive(trigger("batch-b", "12", "alice"));
    Evaluation eval = engine.evaluateDue().getFirst();
    String id =
        engine.decide(eval.id(), Outcome.accept("One batch", "One task", Set.of())).ticketId();
    ManagementViews views = new ManagementViews(engine, store, settings, host);
    assertEquals(2, views.overview().path("flow").path("judged").asInt());
    assertEquals(1, views.overview().path("flow").path("accepted").asInt());
    engine.queueReply(
        "failing-reply", id, engine.ticket(id).channels.getFirst(), "A pending reply");
    Effect effect =
        store.effects().stream()
            .filter(e -> e.kind() == EffectKind.REPLY)
            .findFirst()
            .orElseThrow();
    store.failEffect(effect.id(), "temporary send failure", System.currentTimeMillis());
    List<JsonNode> problems = new ArrayList<>();
    views.overview().path("problems").forEach(problems::add);
    JsonNode failed =
        problems.stream()
            .filter(p -> p.path("kind").asText().equals("outgoing"))
            .findFirst()
            .orElseThrow();
    assertEquals(1, failed.path("outgoing").path("attempts").asInt());
    assertEquals("A pending reply", failed.path("outgoing").path("text").asText());
    engine.markReplySent("failing-reply", "receipt");
    assertFalse(views.overview().path("problems").toString().contains("temporary send failure"));
  }

  @Test
  void sourceLifecyclePrioritizesOwnSetupErrorsAndThreadStagesUseDomainFacts() {
    String ticket = accept("stage-origin", "12");
    ObjectNode setup =
        object(
            "problem",
            object("place", "source:github", "detail", "account failed"),
            "feishuProblem",
            null);
    ObjectNode health =
        object("github", object("state", "ready"), "feishu", object("state", "ready"));
    ManagementHost live =
        new ManagementHost() {
          public JsonNode setup() {
            return setup;
          }

          public JsonNode health() {
            return health;
          }
        };
    ManagementViews views = new ManagementViews(engine, store, settings, live);
    assertEquals(
        "error",
        views.sources().stream()
            .filter(row -> row.path("id").asText().equals("github"))
            .findFirst()
            .orElseThrow()
            .path("state")
            .asText());
    assertEquals(
        "off",
        views.sources().stream()
            .filter(row -> row.path("id").asText().equals("feishu"))
            .findFirst()
            .orElseThrow()
            .path("state")
            .asText());
    ObjectNode feishu = (ObjectNode) settings.value("source:feishu");
    feishu.put("enabled", true);
    settings.save("source:feishu", feishu, 0, "panel");
    assertEquals(
        "setup",
        views.sources().stream()
            .filter(row -> row.path("id").asText().equals("feishu"))
            .findFirst()
            .orElseThrow()
            .path("state")
            .asText());
    setup.set("problem", node(null));
    setup.set("feishuProblem", object("place", "feishu", "detail", "bot failed"));
    assertEquals(
        "ready",
        views.sources().stream()
            .filter(row -> row.path("id").asText().equals("github"))
            .findFirst()
            .orElseThrow()
            .path("state")
            .asText());
    assertEquals(
        "error",
        views.sources().stream()
            .filter(row -> row.path("id").asText().equals("feishu"))
            .findFirst()
            .orElseThrow()
            .path("state")
            .asText());
    setup.set("feishuProblem", node(null));
    health.set("github", object("state", "paused", "code", "sourceOff"));
    assertEquals(
        "starting",
        views.sources().stream()
            .filter(row -> row.path("id").asText().equals("github"))
            .findFirst()
            .orElseThrow()
            .path("state")
            .asText());
    ThreadState thread =
        engine.threads().stream().filter(t -> t.key.equals("github:12")).findFirst().orElseThrow();
    thread.metadata.put("label", "Human-readable issue title");
    thread.metadata.put("facts", Map.of("state", "open"));
    JsonNode row = views.threadLine(thread);
    assertEquals("subscribed", row.path("stage").asText());
    assertEquals("Human-readable issue title", row.path("title").asText());
    assertEquals(1, row.path("subscriptions").asInt());
    assertEquals("open", row.path("facts").path("state").asText());
    thread.metadata.put("pollError", "temporary failure");
    assertEquals("error", views.threadLine(thread).path("stage").asText());
  }

  @Test
  void sourceImagesRemainWholeAndKeepWorkerAuthorization() {
    String id = accept("image-origin", "12");
    String data = "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[12000]);
    List<String> reached = new ArrayList<>();
    ManagementHost imageHost =
        new ManagementHost() {
          public void authorizeSourceRead(String resource, String ticket) {
            if (!id.equals(ticket) || !resource.equals("feishu/image/reached/key"))
              throw new ApiException(403, "Image not reached");
          }

          public JsonNode readSource(String resource, String ticket) {
            reached.add(ticket);
            return object("dataUrl", data, "mediaType", "image/png", "bytes", 12000);
          }
        };
    ResourceService resources = new ResourceService(engine, store, settings, prompts, imageHost);
    JsonNode image = resources.read("feishu/image/reached/key", 0, 4000, id);
    assertEquals(data, image.path("dataUrl").asText());
    assertEquals(List.of(id), reached);
    assertFalse(image.has("next_offset"));
    assertThrows(
        ApiException.class, () -> resources.read("feishu/image/not-reached/key", 0, 4000, id));
    assertEquals(1, reached.size());
    ManagementHost invalid =
        new ManagementHost() {
          public void authorizeSourceRead(String r, String t) {}

          public JsonNode readSource(String r, String t) {
            return object(
                "dataUrl", "data:image/svg+xml;base64,PHN2Zy8+", "mediaType", "image/svg+xml");
          }
        };
    assertThrows(
        ApiException.class,
        () ->
            new ResourceService(engine, store, settings, prompts, invalid)
                .read("feishu/image/reached/key", 0, 4000, id));
  }

  @Test
  void sourceSyncAndSubscriptionMutationsUseVersionedStore() throws Exception {
    int revision = settings.revision("policy");
    assertEquals(200, call("policy-sync/github", "POST", null).statusCode());
    assertTrue(settings.revision("policy") > revision);
    assertEquals(
        "bob",
        settings
            .value("policy")
            .path("sources")
            .path("github")
            .path("allowed")
            .get(0)
            .path("id")
            .asText());
  }
}
