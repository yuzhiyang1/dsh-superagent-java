package io.github.dsh.runtime;

import static io.github.dsh.core.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.dsh.adapters.CodingExecutor;
import io.github.dsh.core.*;
import io.github.dsh.execution.ExecutionSettings;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real preparation processes and model HTTP requests through the production executor wrapper. */
class LaunchBoundaryTest {
  @TempDir Path root;
  SqliteStore store;
  Engine engine;
  OrchestratorRuntime runtime;
  HttpServer model;
  java.util.concurrent.ExecutorService modelThreads;
  final AtomicInteger requests = new AtomicInteger();
  final List<String> modelRequests = new CopyOnWriteArrayList<>();
  final CountDownLatch modelEntered = new CountDownLatch(1), releaseModel = new CountDownLatch(1);
  boolean holdModel;
  final TestClock clock = new TestClock();

  static final class TestClock extends Clock {
    volatile long time = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(time);
    }
  }

  @BeforeEach
  void open() throws Exception {
    store = new SqliteStore(root.resolve("state.db"));
    engine = new Engine(store, clock);
    engine.setPolicy(
        new Policy(
            "v1",
            AcceptMode.AUTO,
            Map.of("local", new SourcePolicy(Set.of("alice"), Set.of("bot"), Map.of())),
            Set.of()));
    model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    modelThreads = Executors.newVirtualThreadPerTaskExecutor();
    model.setExecutor(modelThreads);
    model.createContext(
        "/chat/completions",
        exchange -> {
          modelRequests.add(
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
          requests.incrementAndGet();
          modelEntered.countDown();
          try {
            if (holdModel && !releaseModel.await(5, TimeUnit.SECONDS))
              throw new AssertionError("Model response was not released");
            byte[] bytes =
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Prepared safely\"}}]}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    model.start();
  }

  @AfterEach
  void close() {
    releaseModel.countDown();
    if (runtime != null) runtime.close();
    if (model != null) model.stop(0);
    if (modelThreads != null) modelThreads.close();
    if (store != null) store.close();
  }

  String ticket() {
    return ticket(Set.of());
  }

  String ticket(Set<String> rules) {
    engine.receive(
        new Trigger(
            "request",
            "local",
            List.of(new ThreadRef("local", "thread")),
            "alice",
            true,
            true,
            true,
            "issue",
            clock.instant().toString(),
            "Prepare workspace",
            new Channel("local", "thread", Map.of())));
    return engine.decideTrigger("request", Outcome.accept("prepare", "scope", rules)).ticketId();
  }

  void start(String prepare) throws Exception {
    start(prepare, Map.of());
  }

  void start(String prepare, Map<String, String> environment) throws Exception {
    var settings =
        ExecutionSettings.builder(root.resolve("host"), root.resolve("work"))
            .allowShell(true)
            .allowWrites(true)
            .prepareCommand(prepare)
            .environment(environment)
            .shutdownTimeout(Duration.ofSeconds(1))
            .build();
    var executor =
        new CodingExecutor(
            URI.create("http://127.0.0.1:" + model.getAddress().getPort() + "/"),
            "",
            "fixture",
            settings);
    runtime = new OrchestratorRuntime(engine, store, executor, message -> "receipt", clock);
  }

  static void await(BooleanSupplier ready) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(ready.getAsBoolean(), "Condition was not reached");
  }

  @Test
  void pauseDuringPrepareDefersModelAndInputDeliveryUntilResume() throws Exception {
    start(
        "printf x >> attempts; touch preparing; while [ ! -e release ]; do sleep 0.02; done; mkdir"
            + " \"$DSH_SUPERAGENT_DIR\"");
    String id = ticket();
    runtime.pump();
    await(() -> Files.exists(root.resolve("work/preparing")));
    assertEquals(AssignmentStatus.PENDING, engine.ticket(id).latestAssignment().status);
    engine.setProcessing(true, 60000);
    Files.createFile(root.resolve("work/release"));
    runtime.drain(20);
    Ticket paused = engine.ticket(id);
    assertEquals(0, requests.get());
    assertEquals(AssignmentStatus.PENDING, paused.latestAssignment().status);
    assertNull(paused.latestAssignment().startedAt);
    assertNull(paused.latestAssignment().deliveredAt);
    assertFalse(paused.undelivered.isEmpty());
    assertTrue(store.effects().stream().anyMatch(e -> e.kind() == EffectKind.START));

    engine.setProcessing(false, 60000);
    clock.time += 2000;
    runtime.drain(20);
    assertEquals(1, requests.get());
    assertEquals("x", Files.readString(root.resolve("work/attempts")));
    assertEquals(ReportKind.WAITING, engine.ticket(id).latestAssignment().report.kind());
    assertNotNull(engine.ticket(id).latestAssignment().startedAt);
  }

  @Test
  void pauseDoesNotInterruptAnAlreadyStartedModelTurn() throws Exception {
    holdModel = true;
    start("");
    String id = ticket();
    runtime.pump();
    assertTrue(modelEntered.await(5, TimeUnit.SECONDS));
    engine.setProcessing(true, 60000);
    releaseModel.countDown();
    runtime.drain(20);
    assertTrue(engine.processing().paused());
    assertEquals(1, requests.get());
    assertEquals(ReportKind.WAITING, engine.ticket(id).latestAssignment().report.kind());
  }

  @Test
  void stoppedPreparationNeverStartsAStaleModelTurn() throws Exception {
    start(
        "touch preparing; while [ ! -e release ]; do sleep 0.02; done; mkdir"
            + " \"$DSH_SUPERAGENT_DIR\"");
    String id = ticket();
    runtime.pump();
    await(() -> Files.exists(root.resolve("work/preparing")));
    engine.stopTicket(id);
    Files.createFile(root.resolve("work/release"));
    runtime.drain(20);
    assertEquals(0, requests.get());
    assertTrue(engine.ticket(id).stopped);
  }

  @Test
  void failedPreparationReportsUsefulRedactedOutputWithoutAutomaticRetry() throws Exception {
    start(
        "printf x >> attempts; printf 'DIAGNOSTIC_STDOUT\\n"
            + "'; printf 'DIAGNOSTIC_STDERR\\n"
            + "Authorization: Bearer fake-secret-value\\n"
            + "' >&2; exit 7");
    String id = ticket();
    runtime.drain(20);
    Report report = engine.ticket(id).latestAssignment().report;
    assertEquals(ReportKind.FAILED, report.kind());
    assertTrue(report.summary().contains("exit 7"));
    assertTrue(report.summary().contains("DIAGNOSTIC_STDOUT"));
    assertTrue(report.summary().contains("DIAGNOSTIC_STDERR"));
    assertFalse(report.summary().contains("fake-secret-value"));
    assertTrue(report.summary().contains("[REDACTED]"));
    clock.time += 60000;
    runtime.drain(20);
    assertEquals("x", Files.readString(root.resolve("work/attempts")));
    assertEquals(0, requests.get());
  }

  @Test
  void inputsAndPolicyChangesDuringPrepareReachTheFirstModelTurn() throws Exception {
    var sources = Map.of("local", new SourcePolicy(Set.of("alice"), Set.of("bot"), Map.of()));
    engine.setPolicy(
        new Policy(
            "v2", AcceptMode.AUTO, sources, Set.of("rule"), Map.of("rule", "original-rule")));
    start(
        "touch preparing; while [ ! -e release ]; do sleep 0.02; done; mkdir"
            + " \"$DSH_SUPERAGENT_DIR\"");
    String id = ticket(Set.of("rule"));
    runtime.pump();
    await(() -> Files.exists(root.resolve("work/preparing")));
    engine.receive(
        new Trigger(
            "followup",
            "local",
            List.of(new ThreadRef("local", "thread")),
            "alice",
            true,
            true,
            true,
            "issue",
            clock.instant().toString(),
            "FOLLOWUP_DURING_PREPARE",
            new Channel("local", "thread", Map.of())));
    engine.setPolicy(
        new Policy(
            "v3",
            AcceptMode.AUTO,
            sources,
            Set.of("rule"),
            Map.of("rule", "UPDATED_POLICY_DURING_PREPARE")));
    Files.createFile(root.resolve("work/release"));
    runtime.drain(20);
    assertEquals(1, requests.get());
    assertTrue(modelRequests.getFirst().contains("FOLLOWUP_DURING_PREPARE"));
    assertTrue(modelRequests.getFirst().contains("UPDATED_POLICY_DURING_PREPARE"));
    Ticket result = engine.ticket(id);
    assertTrue(result.inputs.stream().anyMatch(i -> i.kind == InputKind.POLICY_CHANGED));
    assertTrue(result.inputs.stream().allMatch(i -> i.readAt != null));
    assertTrue(result.undelivered.isEmpty());
  }

  @Test
  void configuredEnvironmentCredentialsAreRemovedFromPrepareReports() throws Exception {
    start(
        "printf 'cannot clone repository: '; printf '%s' \"$AUTH_HEADER\"; exit 7",
        Map.of("AUTH_HEADER", "opaque-configured-credential"));
    String id = ticket();
    runtime.drain(20);
    String summary = engine.ticket(id).latestAssignment().report.summary();
    assertTrue(summary.contains("cannot clone repository"));
    assertFalse(summary.contains("opaque-configured-credential"));
    assertTrue(summary.contains("[REDACTED]"));
  }

  @Test
  void unrelatedExceptionMessagesRemainHidden() {
    runtime =
        new OrchestratorRuntime(
            engine,
            store,
            (ticket, assignment) -> {
              throw new IllegalStateException("unrelated-private-payload");
            },
            message -> "receipt",
            clock);
    String id = ticket();
    runtime.drain(20);
    String summary = engine.ticket(id).latestAssignment().report.summary();
    assertTrue(summary.contains("IllegalStateException"));
    assertFalse(summary.contains("unrelated-private-payload"));
  }
}
