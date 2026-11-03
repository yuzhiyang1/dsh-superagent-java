package io.github.dsh.runtime;

import io.github.dsh.core.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.dsh.core.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeTest {
    @TempDir Path dir;
    SqliteStore store;
    Engine engine;
    TestClock clock = new TestClock();
    static final class TestClock extends Clock {
        long time = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(time); }
    }
    @BeforeEach void open() { store = new SqliteStore(dir.resolve("state.db")); engine = new Engine(store,clock); engine.setPolicy(new Policy("v1",AcceptMode.OBSERVE,Map.of("local",new SourcePolicy(Set.of("alice"),Set.of(),Map.of())),Set.of())); }
    @AfterEach void close() { store.close(); }
    String createTicket() {
        engine.receive(new Trigger("request","local",List.of(new ThreadRef("local","thread")),"alice",true,true,true,"issue",clock.instant().toString(),"read",new Channel("local","thread",Map.of())));
        return engine.decide(engine.evaluateDue().getFirst().id(),Outcome.accept("read","read workspace",Set.of())).ticketId();
    }
    @Test void acceptedTicketRunsAndResultGoesThroughDurableOutbox() {
        String id = createTicket(); List<OutboxMessage> sent = new ArrayList<>(); AtomicInteger executions = new AtomicInteger();
        OrchestratorRuntime runtime = new OrchestratorRuntime(engine,store,(ticket,a) -> { executions.incrementAndGet(); return new Report(ReportKind.READY,"done"); }, message -> { sent.add(message); return "receipt"; },clock);
        assertEquals(3,runtime.drain(20)); assertEquals(1,executions.get()); assertEquals(1,sent.size());
        assertEquals(TicketStatus.ENDED,engine.ticket(id).status); assertEquals("sent",engine.outboxMessage(sent.getFirst().id()).status());
        assertEquals(0,runtime.drain(20));
    }
    @Test void interruptedClaimAndRunningAssignmentRecoverWithoutLosingTicket() {
        String id = createTicket(); Effect claimed = store.claimEffects(clock.millis(),1).getFirst();
        engine.assignmentStarted(id,claimed.payload().get("assignmentId"));
        store.close(); store = new SqliteStore(dir.resolve("state.db")); engine = new Engine(store,clock); store.recoverEffects();
        AtomicInteger executions = new AtomicInteger();
        OrchestratorRuntime runtime = new OrchestratorRuntime(engine,store,(ticket,a) -> { executions.incrementAndGet(); return new Report(ReportKind.READY,"recovered"); },message -> "receipt",clock);
        runtime.drain(20); assertEquals(1,executions.get()); assertEquals(TicketStatus.ENDED,engine.ticket(id).status); assertEquals(0,store.effectCount());
    }
    @Test void externalFailureBacksOffWithoutLosingEffectOrEndingTicket() {
        String id = createTicket(); AtomicInteger attempts = new AtomicInteger();
        OrchestratorRuntime runtime = new OrchestratorRuntime(engine,store,(ticket,a) -> { if (attempts.getAndIncrement() == 0) throw new java.net.ConnectException("private error"); return new Report(ReportKind.READY,"done"); },message -> "receipt",clock);
        assertEquals(1,runtime.drain(20)); assertEquals(TicketStatus.OPEN,engine.ticket(id).status); assertEquals(0,runtime.drain(20));
        clock.time += 2000; assertEquals(3,runtime.drain(20)); assertEquals(TicketStatus.ENDED,engine.ticket(id).status); assertEquals(2,attempts.get());
    }
    @Test void channelFailureRetainsPendingReceiptThenRetries() {
        createTicket(); AtomicInteger sends = new AtomicInteger();
        OrchestratorRuntime runtime = new OrchestratorRuntime(engine,store,(ticket,a) -> new Report(ReportKind.READY,"done"),message -> { if (sends.getAndIncrement() == 0) throw new java.io.IOException("offline"); return "receipt"; },clock);
        runtime.drain(20); assertEquals(1,store.effectCount()); clock.time += 5000; runtime.drain(20); assertEquals(2,sends.get()); assertEquals(0,store.effectCount());
    }
    @Test void stoppedTicketNeverExecutesStaleStartEffect() {
        String id = createTicket(); engine.stopTicket(id); AtomicInteger calls = new AtomicInteger();
        OrchestratorRuntime runtime = new OrchestratorRuntime(engine,store,(ticket,a) -> { calls.incrementAndGet(); return new Report(ReportKind.READY,"bad"); },message -> "receipt",clock);
        runtime.drain(20); assertEquals(0,calls.get()); assertEquals(0,store.effectCount());
    }
    @Test void reportAndOutboxAreAtomicEvenWhenCallerCrashes() {
        String id = createTicket(); Ticket ticket = engine.ticket(id); String assignment = ticket.latestAssignment().id;
        engine.assignmentStarted(id,assignment);
        assertThrows(IllegalStateException.class,() -> store.atomic(() -> {
            engine.report(id,assignment,new Report(ReportKind.READY,"done"));
            engine.queueReply("result",id,ticket.channels.getFirst(),"done");
            throw new IllegalStateException("process failure before commit");
        }));
        assertEquals(TicketStatus.OPEN,engine.ticket(id).status); assertNull(engine.outboxMessage("result"));
    }
    @Test void startConsumesQueuedInputsOnceIncludingPolicyNotes() {
        createTicket();
        engine.setPolicy(new Policy("v2",AcceptMode.OBSERVE,Map.of("local",new SourcePolicy(Set.of(),Set.of(),Map.of())),Set.of()));
        AtomicInteger calls = new AtomicInteger();
        OrchestratorRuntime runtime = new OrchestratorRuntime(engine,store,(ticket,a) -> { calls.incrementAndGet(); return new Report(ReportKind.WAITING,"waiting"); },message -> "receipt",clock);
        runtime.drain(20); assertEquals(1,calls.get());
        assertTrue(engine.tickets().getFirst().inputs.stream().allMatch(input -> input.acknowledged));
    }

    @Test void inputExecutionFailureIsNotAcknowledgedUntilRetrySucceeds() {
        String id = createTicket(); AtomicInteger calls = new AtomicInteger();
        OrchestratorRuntime runtime = new OrchestratorRuntime(engine,store,(ticket,a) -> {
            int n = calls.incrementAndGet(); if (n == 2) throw new java.io.IOException("input failed");
            return new Report(ReportKind.WAITING,"waiting");
        },message -> "receipt",clock);
        runtime.drain(20);
        engine.receive(new Trigger("follow","local",List.of(new ThreadRef("local","thread")),"alice",true,false,true,"issue",clock.instant().toString(),"continue",null));
        runtime.drain(20);
        assertFalse(engine.ticket(id).inputs.stream().filter(input -> "follow".equals(input.triggerId)).findFirst().orElseThrow().acknowledged);
        clock.time += 2000; runtime.drain(20); assertEquals(3,calls.get());
        assertTrue(engine.ticket(id).inputs.stream().filter(input -> "follow".equals(input.triggerId)).findFirst().orElseThrow().acknowledged);
    }

    @Test void nonTransientLaunchFailureWaitsForManualRetryRatherThanLooping() {
        String id=createTicket(); AtomicInteger calls=new AtomicInteger();
        OrchestratorRuntime runtime=new OrchestratorRuntime(engine,store,(ticket,a) -> {
            calls.incrementAndGet(); throw new IllegalArgumentException("bad launch configuration");
        },message -> "receipt",clock);
        runtime.drain(20); clock.time+=60_000; runtime.drain(20);
        assertEquals(1,calls.get()); assertEquals(ReportKind.FAILED,engine.ticket(id).latestAssignment().report.kind());
        assertTrue(engine.executionHeld(id)); assertEquals(TicketStatus.OPEN,engine.ticket(id).status);
        String previous=engine.ticket(id).latestAssignment().id; engine.retryTicket(id); assertNotEquals(previous,engine.ticket(id).latestAssignment().id);
        runtime.close();
    }

}
