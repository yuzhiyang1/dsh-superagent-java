package io.github.dsh.runtime;

import static io.github.dsh.core.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.dsh.core.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** 调度器使用真实虚拟线程与门闩，验证槽位和停止顺序，不用模型/平台账户。 */
class SchedulerParityTest {
  @TempDir Path dir;
  SqliteStore store;
  Engine engine;
  TestClock clock = new TestClock();
  OrchestratorRuntime runtime;

  static final class TestClock extends Clock {
    volatile long time = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(time);
    }
  }

  @BeforeEach
  void open() {
    store = new SqliteStore(dir.resolve("state.db"));
    engine = new Engine(store, clock);
    engine.setPolicy(
        new Policy(
            "v1",
            AcceptMode.AUTO,
            Map.of("local", new SourcePolicy(Set.of("alice"), Set.of("bot"), Map.of())),
            Set.of("rule")));
  }

  @AfterEach
  void close() {
    if (runtime != null) runtime.close();
    store.close();
  }

  Trigger trigger(String id, String thread, String actor, boolean human) {
    return new Trigger(
        id,
        "local",
        List.of(new ThreadRef("local", thread)),
        actor,
        human,
        true,
        true,
        "issue",
        clock.instant().toString(),
        id,
        new Channel("local", thread, Map.of()));
  }

  String ticket(String name) {
    engine.receive(trigger(name, name, "alice", true));
    return engine.decideTrigger(name, Outcome.accept(name, "scope", Set.of())).ticketId();
  }

  void follow(String ticket, String id, boolean human) {
    Ticket t = engine.ticket(ticket);
    engine.receive(
        trigger(
            id,
            engine.trigger(t.originTriggerId).threads().getFirst().id(),
            human ? "alice" : "bot",
            human));
  }

  void runtime(Executor executor, ChannelSender sender) {
    runtime = new OrchestratorRuntime(engine, store, executor, sender, clock);
    runtime.setMaxWorkers(() -> 1);
  }

  static final class ControlledExecutor implements Executor {
    final Set<String> blocked = ConcurrentHashMap.newKeySet(),
        background = ConcurrentHashMap.newKeySet();
    final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    final BlockingQueue<String> starts = new LinkedBlockingQueue<>();
    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public Report execute(Ticket ticket, Assignment assignment) throws Exception {
      calls.computeIfAbsent(ticket.id, k -> new AtomicInteger()).incrementAndGet();
      starts.add(ticket.id);
      if (blocked.contains(ticket.id) && !release.await(5, TimeUnit.SECONDS))
        throw new AssertionError("test worker release timed out");
      return new Report(ReportKind.WAITING, "idle");
    }

    @Override
    public BackgroundState backgroundState(String id) {
      return new BackgroundState(
          false, background.contains(id), background.contains(id) ? 1 : 0, 0);
    }

    int count(String id) {
      return calls.getOrDefault(id, new AtomicInteger()).get();
    }

    @Override
    public void close() {
      release.countDown();
    }
  }

  @Test
  void freshHumanRequestsNeverBorrowTheExtraFollowupSlot() throws Exception {
    ControlledExecutor executor = new ControlledExecutor();
    runtime(executor, message -> "ok");
    String a = ticket("a"), b = ticket("b");
    executor.blocked.addAll(Set.of(a, b));
    assertEquals(1, runtime.pump());
    assertEquals(a, executor.starts.poll(2, TimeUnit.SECONDS));
    assertNull(executor.starts.poll(80, TimeUnit.MILLISECONDS));
    assertEquals(0, executor.count(b));
    executor.release.countDown();
    runtime.drain(20);
    assertEquals(1, executor.count(a));
    assertEquals(1, executor.count(b));
  }

  @Test
  void ownHumanFollowupGetsExactlyOneExtraSlotAheadOfNewLaunches() throws Exception {
    ControlledExecutor executor = new ControlledExecutor();
    runtime(executor, message -> "ok");
    String a = ticket("a"), d = ticket("d");
    runtime.drain(20);
    executor.starts.clear();
    String busy = ticket("busy"), newWork = ticket("new");
    executor.blocked.addAll(Set.of(a, d, busy, newWork));
    runtime.pump();
    assertEquals(busy, executor.starts.poll(2, TimeUnit.SECONDS));
    follow(a, "follow-a", true);
    follow(d, "follow-d", true);
    runtime.pump();
    assertEquals(a, executor.starts.poll(2, TimeUnit.SECONDS));
    assertNull(executor.starts.poll(80, TimeUnit.MILLISECONDS));
    assertEquals(1, executor.count(d));
    assertEquals(0, executor.count(newWork));
    executor.release.countDown();
    runtime.drain(30);
    assertEquals(2, executor.count(d));
    assertEquals(1, executor.count(newWork));
  }

  @Test
  void statusRequestCanBorrowHumanSlotButContextHumanCannot() throws Exception {
    ControlledExecutor executor = new ControlledExecutor();
    runtime(executor, message -> "ok");
    String idle = ticket("idle");
    runtime.drain(20);
    executor.starts.clear();
    String busy = ticket("busy");
    executor.blocked.addAll(Set.of(idle, busy));
    runtime.pump();
    assertEquals(busy, executor.starts.poll(2, TimeUnit.SECONDS));
    engine.followForContext(idle, new ThreadRef("local", "context"));
    engine.receive(trigger("context-person", "context", "alice", true));
    runtime.pump();
    assertNull(executor.starts.poll(80, TimeUnit.MILLISECONDS)); // 上下文中的人不是工单续接。
    assertTrue(engine.requestStatus(idle));
    runtime.pump();
    assertEquals(idle, executor.starts.poll(2, TimeUnit.SECONDS));
    executor.release.countDown();
    runtime.drain(20);
  }

  @Test
  void backgroundJobsRetainNormalSlotUntilTheyFinish() {
    ControlledExecutor executor = new ControlledExecutor();
    runtime(executor, message -> "ok");
    String a = ticket("a");
    executor.background.add(a);
    runtime.drain(20);
    String b = ticket("b");
    assertEquals(0, runtime.pump());
    assertEquals(0, executor.count(b));
    assertEquals(1, runtime.load().get("running"));
    executor.background.remove(a);
    runtime.drain(20);
    assertEquals(1, executor.count(b));
  }

  @Test
  void heldWorkerRecordsBotsAndNotesWithoutRunningUntilHumanArrives() {
    AtomicInteger calls = new AtomicInteger();
    runtime(
        (ticket, a) -> {
          calls.incrementAndGet();
          return new Report(ReportKind.BLOCKED, "need human");
        },
        message -> "ok");
    String id = ticket("a");
    runtime.drain(20);
    follow(id, "bot", false);
    engine.note(id, "context only");
    runtime.drain(20);
    assertEquals(1, calls.get());
    assertTrue(
        engine.ticket(id).inputs.stream()
            .filter(i -> i.kind == InputKind.NOTE)
            .allMatch(i -> i.held));
    follow(id, "person", true);
    runtime.drain(20);
    assertEquals(2, calls.get());
  }

  @Test
  void replacementWaitsForStopEvenWhenStopIsBackingOff() {
    AtomicInteger executions = new AtomicInteger(), stops = new AtomicInteger();
    runtime(
        new Executor() {
          public Report execute(Ticket ticket, Assignment a) {
            executions.incrementAndGet();
            return new Report(ReportKind.WAITING, "idle");
          }

          public void stop(String ticket, String assignment) throws Exception {
            if (stops.getAndIncrement() == 0) throw new java.net.ConnectException("busy cleanup");
          }
        },
        message -> "ok");
    String id = ticket("a");
    runtime.drain(20);
    String previous = engine.ticket(id).latestAssignment().id;
    engine.report(id, previous, new Report(ReportKind.FAILED, "retry"));
    engine.retryTicket(id);
    runtime.drain(20);
    assertEquals(1, executions.get());
    runtime.drain(20);
    assertEquals(1, executions.get());
    clock.time += 2000;
    runtime.drain(20);
    assertEquals(2, executions.get());
    assertEquals(2, stops.get());
  }

  @Test
  void pauseHoldsLaunchAndReplyButStillAllowsStopping() {
    AtomicInteger executions = new AtomicInteger(),
        sends = new AtomicInteger(),
        stops = new AtomicInteger();
    runtime(
        new Executor() {
          public Report execute(Ticket t, Assignment a) {
            executions.incrementAndGet();
            return new Report(ReportKind.WAITING, "idle");
          }

          public void stop(String t, String a) {
            stops.incrementAndGet();
          }
        },
        message -> {
          sends.incrementAndGet();
          return "ok";
        });
    String id = ticket("a");
    engine.setProcessing(true, 1000);
    assertEquals(0, runtime.drain(20));
    assertEquals(0, executions.get());
    engine.setProcessing(false, 1000);
    runtime.drain(20);
    engine.queueReply("r", id, engine.ticket(id).channels.getFirst(), "hello");
    engine.setProcessing(true, 1000);
    runtime.drain(20);
    assertEquals(0, sends.get());
    engine.stopTicket(id);
    runtime.drain(20);
    assertEquals(1, stops.get());
    assertEquals(0, sends.get());
    engine.setProcessing(false, 1000);
    runtime.drain(20);
    assertEquals(1, sends.get());
  }

  @Test
  void failedReplyBlocksLaterSameConversationUntilThirdFailureButNotOtherTicket() {
    List<String> sent = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger firstAttempts = new AtomicInteger();
    runtime(
        (ticket, a) -> new Report(ReportKind.WAITING, "idle"),
        message -> {
          if (message.id().equals("first")) {
            firstAttempts.incrementAndGet();
            throw new java.io.IOException("offline");
          }
          sent.add(message.id());
          return "ok";
        });
    String a = ticket("a"), b = ticket("b");
    runtime.drain(20);
    engine.queueReply("first", a, engine.ticket(a).channels.getFirst(), "one");
    engine.queueReply("second", a, engine.ticket(a).channels.getFirst(), "two");
    engine.queueReply("other", b, engine.ticket(b).channels.getFirst(), "unrelated");
    runtime.drain(20);
    assertEquals(List.of("other"), sent);
    assertEquals(1, firstAttempts.get());
    assertEquals("error", runtime.health().view().get("reply").state());
    clock.time += 5000;
    runtime.drain(20);
    assertEquals(List.of("other"), sent);
    clock.time += 10000;
    runtime.drain(20);
    assertEquals(3, firstAttempts.get());
    assertTrue(sent.contains("second"));
    engine.withdrawReply("first");
    runtime.pump();
    assertEquals("ready", runtime.health().view().get("reply").state());
  }

  @Test
  void orderedMainRelayDoesNotLetLaterMessageOvertakeFailedHead() {
    List<String> messages = new ArrayList<>();
    AtomicInteger firstAttempts = new AtomicInteger();
    runtime((t, a) -> new Report(ReportKind.WAITING, "idle"), message -> "ok");
    runtime.setMainHandler(
        effect -> {
          if (effect.key().equals("one") && firstAttempts.getAndIncrement() == 0)
            throw new java.net.ConnectException("offline");
          messages.add(effect.key());
        });
    store.enqueue(EffectKind.RELAY, "one", Map.of(), clock.millis());
    store.enqueue(EffectKind.RELAY, "two", Map.of(), clock.millis());
    runtime.drain(20);
    assertTrue(messages.isEmpty());
    clock.time += 2000;
    runtime.drain(20);
    assertEquals(List.of("one", "two"), messages);
  }

  @Test
  void transientLaunchMakesThreeRetriesThenReportsFailed() {
    AtomicInteger calls = new AtomicInteger();
    runtime(
        (ticket, a) -> {
          calls.incrementAndGet();
          throw new java.net.ConnectException("temporary");
        },
        message -> "ok");
    String id = ticket("a");
    runtime.drain(20);
    clock.time += 2000;
    runtime.drain(20);
    clock.time += 4000;
    runtime.drain(20);
    clock.time += 8000;
    runtime.drain(20);
    assertEquals(4, calls.get());
    assertEquals(ReportKind.FAILED, engine.ticket(id).latestAssignment().report.kind());
    assertTrue(engine.executionHeld(id));
    assertEquals(0, store.effectCount());
  }

  @Test
  void withdrawnReplyIsNeverSentAndReceiptStaysDropped() {
    AtomicInteger sends = new AtomicInteger();
    runtime(
        (t, a) -> new Report(ReportKind.WAITING, "idle"),
        message -> {
          sends.incrementAndGet();
          return "ok";
        });
    String id = ticket("a");
    runtime.drain(20);
    engine.queueReply("drop", id, engine.ticket(id).channels.getFirst(), "old attention");
    assertTrue(engine.withdrawReply("drop"));
    runtime.drain(20);
    assertEquals(0, sends.get());
    assertEquals("dropped", engine.outboxMessage("drop").status());
  }

  @Test
  void laterDefersWithoutCountingFailureOrBusyLooping() {
    AtomicInteger calls = new AtomicInteger();
    runtime((t, a) -> new Report(ReportKind.WAITING, "idle"), message -> "ok");
    runtime.setMainHandler(
        effect -> {
          if (calls.incrementAndGet() == 1) throw new OrchestratorRuntime.Later("session is busy");
        });
    store.enqueue(EffectKind.NOTICE, "busy", Map.of(), clock.millis());
    runtime.drain(100);
    assertEquals(1, calls.get());
    Effect waiting = store.effects().getFirst();
    assertEquals(0, waiting.attempts());
    assertEquals(clock.millis() + 2000, waiting.dueAt());
    clock.time += 2000;
    runtime.drain(20);
    assertEquals(2, calls.get());
    assertEquals(0, store.effectCount());
  }

  @Test
  void shutdownCancellationKeepsRunningIntentForRestartInsteadOfMarkingFailed() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), cancel = new CountDownLatch(1);
    runtime(
        new Executor() {
          public Report execute(Ticket t, Assignment a) throws Exception {
            entered.countDown();
            cancel.await();
            throw new InterruptedException("host closing");
          }

          public void close() {
            cancel.countDown();
          }
        },
        message -> "ok");
    String id = ticket("a");
    runtime.pump();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    runtime.close();
    assertEquals(TicketStatus.OPEN, engine.ticket(id).status);
    assertNull(engine.ticket(id).latestAssignment().report);
    assertEquals(1, store.effectCount());
    assertEquals(0, store.effects().getFirst().attempts());
  }
}
