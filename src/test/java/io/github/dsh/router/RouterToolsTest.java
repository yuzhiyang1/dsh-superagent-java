package io.github.dsh.router;

import static io.github.dsh.config.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.dsh.config.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import io.github.dsh.management.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** 不调用模型，直接用工具边界验证主会话能够和不能够做的事。 */
class RouterToolsTest {
  @TempDir Path root;
  SqliteStore store;
  Engine engine;
  MainTools tools;
  VersionedSettings settings;
  PromptService prompts;
  List<String> reads = new ArrayList<>();
  java.util.function.Function<String, JsonNode> source = p -> object("messages", List.of());
  AtomicInteger passes = new AtomicInteger();
  AtomicReference<String> session = new AtomicReference<>("main-one");

  @BeforeEach
  void setup() {
    store = new SqliteStore(root.resolve("state.db"));
    engine = new Engine(store, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    ManagementHost host =
        new ManagementHost() {
          public String masterId() {
            return session.get();
          }

          public JsonNode readSource(String path) {
            reads.add(path);
            return source.apply(path);
          }

          public void requestPass() {
            passes.incrementAndGet();
          }

          public JsonNode synchronizePolicy(String id) {
            return object(
                "allowed",
                List.of(object("id", "ou_alice", "name", "Alice")),
                "admission",
                object());
          }
        };
    settings = new VersionedSettings(store, engine, root, host);
    prompts = new PromptService(store, settings, host);
    tools =
        new MainTools(
            engine,
            store,
            settings,
            prompts,
            new ResourceService(engine, store, settings, prompts, host),
            host);
    engine.setPolicy(
        new Policy(
            "test",
            AcceptMode.OBSERVE,
            Map.of(
                "s",
                new SourcePolicy(
                    Set.of("alice"), Set.of("ci"), Map.of("issue", AdmissionRow.safe()))),
            Set.of("fix", "test")));
  }

  @AfterEach
  void close() {
    store.close();
  }

  Trigger trigger(String id, String thread, boolean addressed, String text) {
    return new Trigger(
        id,
        "s",
        List.of(new ThreadRef("s", thread)),
        "alice",
        true,
        addressed,
        true,
        "issue",
        "2026-01-01T00:00:00Z",
        text,
        new Channel("s", thread, Map.of("topic", thread)),
        Map.of(),
        "https://github.test/" + id,
        null);
  }

  JsonNode call(String name, JsonNode args) throws Exception {
    return MAPPER.valueToTree(tools.invoke("orchestrator_" + name, args));
  }

  JsonNode accept(String id) {
    return object(
        "kind",
        "accept",
        "trigger_id",
        id,
        "title",
        "Fix feature",
        "worktree_name",
        "fix-feature",
        "acknowledgement",
        "I will inspect it.");
  }

  String ticket(String id) throws Exception {
    engine.receive(trigger(id, id, true, "please fix"));
    return call("route", object("decisions", List.of(accept(id))))
        .path("results")
        .get(0)
        .path("ticket")
        .path("id")
        .asText();
  }

  Evaluation inferred(String id) {
    engine.receive(trigger(id, id, false, "request clarified"));
    return engine.evaluateDue().getFirst();
  }

  ObjectNode assessment() {
    return object(
        "decision",
        "accept",
        "title",
        "Fix feature",
        "scope",
        "fix only",
        "reason",
        "Matches requested fix",
        "worktree_name",
        "fix-feature",
        "intent_evidence",
        "User asks implementation",
        "direction",
        object(
            "implementation_requested",
            true,
            "target_basis",
            "specified",
            "requires_design_decision",
            false,
            "evidence",
            "Concrete desired behavior"),
        "rule_assessments",
        List.of(
            object("rule_id", "fix", "matched", true, "reason", "Fix request"),
            object("rule_id", "test", "matched", false, "reason", "Not a test request")));
  }

  @Test
  void directRouteIndependentBatchAndAcceptedReplay() throws Exception {
    engine.receive(trigger("a", "a", true, "please fix"));
    engine.receive(trigger("b", "b", true, "please fix"));
    JsonNode results =
        call(
                "route",
                object(
                    "decisions",
                    List.of(object("kind", "bad", "trigger_id", "a"), accept("a"), accept("b"))))
            .path("results");
    assertTrue(results.get(0).has("refused"));
    assertTrue(results.get(1).has("ticket"));
    assertTrue(results.get(2).has("ticket"));
    assertEquals(2, engine.tickets().size());
    JsonNode replay = call("route", object("decisions", List.of(accept("a"))));
    assertEquals(
        results.get(1).path("ticket").path("id"),
        replay.path("results").get(0).path("ticket").path("id"));
    assertNotEquals(engine.tickets().get(0).shortName, engine.tickets().get(1).shortName);
  }

  @Test
  void offThreadExplicitStopRecordsDecisionAndAttachRequiresReference() throws Exception {
    String id = ticket("a");
    engine.receive(trigger("stop", "different", true, "stop " + id));
    JsonNode row =
        call(
                "route",
                object(
                    "decisions",
                    List.of(object("kind", "stop", "trigger_id", "stop", "ticket_id", id))))
            .path("results")
            .get(0);
    assertFalse(row.has("refused"), row.toString());
    assertTrue(engine.ticket(id).stopped);
    String second = ticket("b");
    engine.receive(trigger("bad", "other", true, "unrelated"));
    engine.receive(trigger("linked", "linked", true, "for ticket " + second));
    JsonNode attached =
        call(
                "route",
                object(
                    "decisions",
                    List.of(
                        object("kind", "attach", "trigger_id", "bad", "ticket_id", second),
                        object("kind", "attach", "trigger_id", "linked", "ticket_id", second))))
            .path("results");
    assertTrue(attached.get(0).has("refused"));
    assertFalse(attached.get(1).has("refused"), attached.toString());
  }

  @Test
  void inferredObservePreservesWholeAuditWithoutTicket() throws Exception {
    inferred("inferred");
    call("eval", object("trigger_id", "inferred"));
    JsonNode result =
        call("eval_decide", object("trigger_id", "inferred", "assessment", assessment()));
    assertEquals("IGNORE", result.path("kind").asText());
    assertEquals("observed", result.path("reason").asText());
    assertTrue(result.path("audit").path("would_accept").asBoolean());
    assertEquals(2, result.path("audit").path("rule_assessments").size());
    assertTrue(engine.tickets().isEmpty());
  }

  @Test
  void allRulesExactlyOnceAndDirectionCannotBeBypassed() {
    ObjectNode a = assessment();
    ((ArrayNode) a.path("rule_assessments")).remove(1);
    assertThrows(
        IllegalArgumentException.class,
        () -> AssessmentValidator.validate(a, Set.of("fix", "test")));
    ObjectNode b = assessment();
    ((ArrayNode) b.path("rule_assessments")).add(b.path("rule_assessments").get(0));
    assertThrows(
        IllegalArgumentException.class,
        () -> AssessmentValidator.validate(b, Set.of("fix", "test")));
    ObjectNode c = assessment();
    ((ObjectNode) c.path("direction")).put("requires_design_decision", true);
    assertEquals(
        "directionUnsettled", AssessmentValidator.validate(c, Set.of("fix", "test")).reason());
    ObjectNode d = assessment();
    d.put("decision", "ignore");
    d.remove("title");
    assertThrows(
        IllegalArgumentException.class,
        () -> AssessmentValidator.validate(d, Set.of("fix", "test")));
  }

  @Test
  void sourceNativeMessagesExpandAndRemainScopedWithStablePagination() throws Exception {
    Evaluation e = inferred("native");
    source =
        p ->
            object(
                "messages",
                List.of(
                    object("id", "https://github.test/native", "text", "x".repeat(9000)),
                    object("id", "native-neighbor", "text", "y".repeat(5000))));
    JsonNode card = MAPPER.valueToTree(tools.candidateCard(e));
    assertEquals(4000, card.path("source_window").get(0).path("text").asText().length());
    JsonNode first =
        call(
            "eval_read",
            object("trigger_id", "native", "resource", "message", "message_id", "native-neighbor"));
    assertEquals(4000, first.path("text").asText().length());
    JsonNode tail =
        call(
            "eval_read",
            object(
                "trigger_id",
                "native",
                "resource",
                "message",
                "message_id",
                "native-neighbor",
                "offset",
                4000));
    assertEquals(1000, tail.path("text").asText().length());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            call(
                "eval_read",
                object("trigger_id", "native", "resource", "message", "message_id", "unknown")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            call(
                "eval_read",
                object("trigger_id", "native", "resource", "thread", "cursor", "invented")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            call(
                "eval_read",
                object("trigger_id", "native", "resource", "file", "path", "a", "cursor", "bad")));
  }

  @Test
  void reachedImagesUseTheirMessageAnchorAndUnknownImageNeverCallsSource() throws Exception {
    inferred("image");
    source =
        p ->
            p.contains("/image?")
                ? object("dataUrl", "data:image/png;base64,AA==")
                : object(
                    "messages",
                    List.of(object("id", "native_neighbor", "text", "[image img_abc]")));
    call("eval", object("trigger_id", "image"));
    JsonNode result =
        call(
            "eval_read",
            object(
                "trigger_id", "image", "resource", "image", "image_id", "native_neighbor/img_abc"));
    assertTrue(result.has("dataUrl"));
    assertTrue(reads.getLast().contains("message_id=native_neighbor&image_id=img_abc"));
    int before = reads.size();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            call(
                "eval_read",
                object("trigger_id", "image", "resource", "image", "image_id", "foreign/img_abc")));
    assertEquals(before, reads.size());
  }

  @Test
  void contextWindowAndStubsAreBoundedAndCursorMustBeReached() throws Exception {
    inferred("window");
    AtomicInteger pages = new AtomicInteger();
    source =
        p -> {
          int page = pages.getAndIncrement();
          return object(
              "messages",
              java.util.stream.IntStream.range(page * 10, page * 10 + 10)
                  .mapToObj(
                      i ->
                          object(
                              "id",
                              i == 15 ? "https://github.test/window" : "m" + i,
                              "text",
                              "z".repeat(6000)))
                  .toList(),
              "next",
              page == 0 ? "next-page" : "");
        };
    JsonNode card = call("eval", object("trigger_id", "window"));
    assertEquals(2, pages.get());
    assertTrue(card.path("source_stubs").size() > 0);
    for (JsonNode stub : card.path("source_stubs"))
      assertTrue(stub.path("preview").asText().length() <= 50);
    call("eval_read", object("trigger_id", "window", "resource", "thread", "cursor", "next-page"));
    assertTrue(reads.getLast().endsWith("?cursor=next-page"));
  }

  @Test
  void mainReplyCannotSendWorkerAcknowledgement() throws Exception {
    engine.receive(trigger("question", "question", true, "What does this mean?"));
    assertNotNull(
        call(
            "reply",
            object(
                "trigger_id",
                "question",
                "purpose",
                "answer",
                "text",
                "Here is the explanation.")));
    String id = ticket("reply");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            call("reply", object("ticket_id", id, "purpose", "acknowledgement", "text", "hello")));
  }

  @Test
  void readNoteStatusRetryRequeueUnsubscribeAndPolicySyncAreLiveTools() throws Exception {
    String id = ticket("tools");
    assertNotNull(call("read", object("resource", "tickets")));
    assertNotNull(call("ticket_note", object("ticket_id", id, "text", "Useful context")));
    assertNotNull(call("ticket_request_status", object("ticket_id", id)));
    Ticket t = engine.ticket(id);
    engine.assignmentStarted(id, t.latestAssignment().id);
    engine.report(id, t.latestAssignment().id, new Report(ReportKind.FAILED, "failure"));
    assertNotNull(call("ticket_retry", object("ticket_id", id)));
    assertTrue(call("trigger_requeue", object("trigger_id", "tools")).has("queued"));
    assertTrue(call("thread_unsubscribe", object("thread", "s:tools")).has("removed"));
    assertEquals("feishu", call("policy_sync", object("source", "feishu")).path("source").asText());
    assertTrue(passes.get() >= 7);
  }

  @Test
  void policySyncActuallySavesAndReportsMembershipDifference() throws Exception {
    JsonNode result = call("policy_sync", object("source", "feishu"));
    assertEquals(1, result.path("allowed").asInt());
    assertEquals("Alice", result.path("added").get(0).asText());
    assertEquals(
        "ou_alice",
        settings
            .value("policy")
            .path("sources")
            .path("feishu")
            .path("allowed")
            .get(0)
            .path("id")
            .asText());
    assertTrue(engine.policy().sources().get("feishu").allowedActors().contains("ou_alice"));
    assertEquals(0, call("policy_sync", object("source", "feishu")).path("added").size());
  }

  @Test
  void mainActorScopeRestoresAndDoesNotSuppressLaterWorkerEvents() throws Exception {
    String id = ticket("actor");
    assertTrue(
        engine.events(0, 100).stream()
            .filter(e -> e.type().equals("ticket.created"))
            .allMatch(e -> "main".equals(e.payload().get("actor"))));
    Ticket t = engine.ticket(id);
    engine.assignmentStarted(id, t.latestAssignment().id);
    engine.report(id, t.latestAssignment().id, new Report(ReportKind.PROGRESS, "progress"));
    assertTrue(
        engine.events(0, 100).stream()
            .filter(e -> e.type().equals("worker.report"))
            .allMatch(e -> !e.payload().containsKey("actor")));
  }

  @Test
  void initialContextDeadlineInterruptsSlowSourceAndPreviewDoesNotSave() throws Exception {
    Policy policy = engine.policy();
    ObjectNode deployment = settings.value("deployment").deepCopy();
    deployment.put("admissionContextTimeoutMs", 30);
    settings.save("deployment", deployment, settings.revision("deployment"), "panel");
    engine.setPolicy(policy);
    inferred("slow");
    source =
        p -> {
          try {
            Thread.sleep(3000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return object("messages", List.of());
        };
    long start = System.nanoTime();
    JsonNode card = call("eval", object("trigger_id", "slow"));
    assertTrue(card.has("context_error"));
    assertTrue(System.nanoTime() - start < 1_000_000_000L);
    int revision = prompts.revision();
    assertTrue(
        call("prompts_save", object("config", prompts.defaults(), "preview", true))
            .has("previews"));
    assertEquals(revision, prompts.revision());
    ObjectNode changed = prompts.defaults();
    changed.put(
        "communication", changed.path("communication").asText() + "\nKeep replies concise.");
    call("prompts_save", object("config", changed, "baseRevision", revision));
    assertEquals(revision + 1, prompts.revision());
  }

  @Test
  void settingsToolUsesCompleteVersionedBlockAndRejectsStaleRevision() throws Exception {
    int revision = settings.revision("eval");
    ObjectNode value = settings.value("eval").deepCopy();
    value.put("timeoutSeconds", 121);
    call("settings_save", object("block", "eval", "value", value, "revision", revision));
    assertThrows(
        ApiException.class,
        () -> call("settings_save", object("block", "eval", "value", value, "revision", revision)));
  }

  @Test
  void promptPreviewIsBoundedByResourceAndCannotCrossSession() throws Exception {
    JsonNode preview = call("prompts_save", object("config", prompts.defaults(), "preview", true));
    assertEquals(2, preview.path("previews").size());
    assertFalse(preview.has("orchestrator"));
    String resource = preview.path("previews").get(0).path("read").asText();
    JsonNode page = call("read", object("resource", resource, "limit", 100));
    assertTrue(page.path("text").asText().length() <= 100);
    assertEquals(0, prompts.revision());
    session.set("main-two");
    assertThrows(ApiException.class, () -> call("read", object("resource", resource)));
    session.set("main-one");
    assertNotNull(call("read", object("resource", resource, "offset", 100, "limit", 100)));
  }

  @Test
  void schemasExposeExactlyFourteenDomainTools() {
    assertEquals(14, MainTools.schemas().size());
  }
}
