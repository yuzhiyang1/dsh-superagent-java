package io.github.dsh.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static io.github.dsh.core.Model.*;
import static org.junit.jupiter.api.Assertions.*;

/** 从业务不变量读测试，比从数据库实现开始读更容易。 */
class EngineTest {
    @TempDir Path directory;
    SqliteStore store;
    Engine engine;
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    @BeforeEach void start() { store = new SqliteStore(directory.resolve("state.db")); engine = new Engine(store,clock); engine.setPolicy(policy("v1",AcceptMode.OBSERVE)); }
    @AfterEach void close() { store.close(); }
    Policy policy(String version, AcceptMode mode) { return new Policy(version,mode,Map.of("local",new SourcePolicy(Set.of("alice"),Set.of("ci"),Map.of("issue",AdmissionRow.safe()))),Set.of("fix-ci")); }
    Trigger event(String id, String thread, String actor, boolean human, boolean addressed) {
        return new Trigger(id,"local",List.of(new ThreadRef("local",thread)),actor,human,addressed,true,"issue",clock.instant().toString(),"Inspect project " + id,new Channel("local",thread,Map.of("topic",thread)));
    }
    String accept(String id, String thread) {
        engine.receive(event(id,thread,"alice",true,true));
        Evaluation e = engine.evaluateDue().getFirst();
        return engine.decide(e.id(),Outcome.accept("Inspect","read files",Set.of())).ticketId();
    }
    void finish(String ticketId) {
        Ticket t = engine.ticket(ticketId); engine.assignmentStarted(ticketId,t.latestAssignment().id);
        t.inputs.stream().filter(i -> i.triggerId != null).forEach(i -> engine.acknowledge(ticketId,i.triggerId));
        engine.report(ticketId,t.latestAssignment().id,new Report(ReportKind.READY,"done"));
    }
    @Test void explicitRequestWorksInSafeObserveMode() {
        String ticket = accept("t1","issue1"); assertEquals(TicketStatus.OPEN,engine.ticket(ticket).status);
        assertEquals(Strength.EXPLICIT,engine.ticket(ticket).mandate.strength()); assertEquals(1,store.effectCount());
    }
    @Test void duplicateTriggerAndDuplicateDecisionNeverCreateTwoTickets() {
        Trigger t = event("t1","issue1","alice",true,true); engine.receive(t); Evaluation e = engine.evaluateDue().getFirst();
        Decision first = engine.decide(e.id(),Outcome.accept("a","read",Set.of()));
        assertEquals("duplicate",engine.receive(t)); assertEquals(first,engine.decide(e.id(),Outcome.accept("b","write",Set.of())));
        assertEquals(1,engine.tickets().size());
    }
    @Test void inferredObserveRecordsWouldAcceptWithoutDispatching() {
        engine.receive(event("t1","issue1","alice",true,false)); Evaluation e = engine.evaluateDue().getFirst();
        assertThrows(IllegalStateException.class,() -> engine.decide(e.id(),Outcome.accept("a","read",Set.of("fix-ci"))));
        assertEquals(DecisionKind.IGNORE,engine.decide(e.id(),new Outcome(DecisionKind.IGNORE,null,null,Set.of(),"observed",null,null,null,null,Map.of("would_accept",true))).kind());
        assertTrue(engine.tickets().isEmpty()); assertEquals(0,store.effectCount());
    }
    @Test void inferredAutoRequiresEnabledRulesAndOffRejectsBots() {
        engine.setPolicy(policy("v2",AcceptMode.AUTO)); engine.receive(event("ci1","issue1","ci",false,false));
        Evaluation e = engine.evaluateDue().getFirst();
        assertThrows(IllegalArgumentException.class,() -> engine.decide(e.id(),Outcome.accept("a","read",Set.of("not-enabled"))));
        assertEquals(DecisionKind.ACCEPT,engine.decide(e.id(),Outcome.accept("a","read",Set.of("fix-ci"))).kind());
        engine.setPolicy(policy("v3",AcceptMode.OFF)); engine.receive(event("ci2","issue2","ci",false,true));
        assertTrue(engine.evaluateDue().isEmpty());
    }
    @Test void outsiderAndUnknownBotCannotStartWork() {
        engine.receive(event("x","a","mallory",true,true)); engine.receive(event("y","b","bot",false,true));
        assertTrue(engine.evaluateDue().isEmpty()); assertEquals(2,engine.decisions().size()); assertTrue(engine.tickets().isEmpty());
    }
    @Test void sourceIdentitiesNeverLeakAcrossSources() {
        Trigger t = new Trigger("x","other",List.of(new ThreadRef("other","thread")),"alice",true,true,true,"issue",clock.instant().toString(),"work",null);
        engine.receive(t); assertTrue(engine.evaluateDue().isEmpty());
    }
    @Test void policyChangeMakesInFlightEvalUnavailable() {
        engine.receive(event("a","a","alice",true,true)); Evaluation e = engine.evaluateDue().getFirst();
        engine.setPolicy(policy("v2",AcceptMode.AUTO));
        assertEquals(DecisionKind.UNAVAILABLE,engine.decide(e.id(),Outcome.accept("a","read",Set.of())).kind()); assertTrue(engine.tickets().isEmpty());
        Evaluation fresh = engine.evaluateDue().getFirst(); assertEquals("v2",fresh.policyVersion());
        assertEquals(List.of("a"),fresh.triggerIds()); assertNotEquals(e.id(),fresh.id());
        assertEquals(DecisionKind.ACCEPT,engine.decide(fresh.id(),Outcome.accept("a","read",Set.of())).kind());
    }
    @Test void onlyOneEvalPerThreadAndLaterTriggersFormNextBatch() {
        engine.receive(event("a","a","alice",true,true)); Evaluation first = engine.evaluateDue().getFirst();
        engine.receive(event("b","a","alice",true,true)); assertEquals(first.id(),engine.evaluateDue().getFirst().id());
        engine.decide(first.id(),Outcome.ignore("not work")); Evaluation next = engine.evaluateDue().getFirst();
        assertEquals(List.of("b"),next.triggerIds());
    }
    @Test void followupIsContextNotNewAuthorityAndResumesEndedTicket() {
        String id = accept("a","a"); finish(id);
        engine.receive(event("follow","a","mallory",true,false));
        Ticket t = engine.ticket(id); assertEquals(TicketStatus.OPEN,t.status); assertEquals(2,t.assignments.size());
        assertEquals("read files",t.mandate.scope()); assertTrue(engine.evaluateDue().isEmpty());
    }
    @Test void contextOnlySubscriptionDoesNotResumeEndedTicket() {
        String id = accept("a","a"); engine.followThread(id,new ThreadRef("local","other"),true); finish(id);
        engine.receive(event("context","other","alice",true,true));
        assertEquals(TicketStatus.ENDED,engine.ticket(id).status); assertEquals(1,engine.evaluateDue().size());
    }
    @Test void botDoesNotResumeEndedTicketButStillMayBeEvaluated() {
        String id = accept("a","a"); finish(id); engine.receive(event("ci","a","ci",false,true));
        assertEquals(TicketStatus.ENDED,engine.ticket(id).status); assertEquals(1,engine.evaluateDue().size());
    }
    @Test void removedSenderRevokesResumeButDoesNotCancelRunningWork() {
        String id = accept("a","a"); engine.setPolicy(new Policy("v2",AcceptMode.OBSERVE,Map.of(),Set.of()));
        assertEquals(TicketStatus.OPEN,engine.ticket(id).status); finish(id);
        engine.receive(event("follow","a","alice",true,true)); assertEquals(TicketStatus.ENDED,engine.ticket(id).status);
    }
    @Test void explicitStopWithdrawsMandatePermanently() {
        String id = accept("a","a"); engine.receive(event("stop","a","alice",true,false)); engine.stopFromTrigger(id,"stop");
        engine.receive(event("follow","a","alice",true,true)); assertTrue(engine.ticket(id).stopped); assertEquals(TicketStatus.ENDED,engine.ticket(id).status);
        assertThrows(IllegalStateException.class,() -> engine.retryTicket(id));
    }
    @Test void unlistedFollowupAndBotsCannotStop() {
        String id = accept("a","a"); engine.receive(event("stop","a","mallory",true,true));
        assertThrows(IllegalStateException.class,() -> engine.stopFromTrigger(id,"stop"));
        engine.receive(event("bot","a","ci",false,true)); assertThrows(IllegalStateException.class,() -> engine.stopFromTrigger(id,"bot"));
    }
    @Test void failedAssignmentKeepsTicketOpenAndRetryGetsNewAssignment() {
        String id = accept("a","a"); String old = engine.ticket(id).latestAssignment().id;
        engine.assignmentStarted(id,old); engine.report(id,old,new Report(ReportKind.FAILED,"network"));
        assertEquals(TicketStatus.OPEN,engine.ticket(id).status); engine.retryTicket(id);
        assertNotEquals(old,engine.ticket(id).latestAssignment().id);
        assertThrows(IllegalStateException.class,() -> engine.report(id,old,new Report(ReportKind.READY,"late")));
    }
    @Test void handbackWaitsForAllOwnersAndRunsOnlyOnce() {
        String first = accept("a","a"), second = accept("b","b"); engine.followThread(second,new ThreadRef("local","a"),false);
        engine.receive(event("follow","a","alice",true,false)); assertFalse(engine.handback(first,"follow","not my scope"));
        assertTrue(engine.evaluateDue().isEmpty()); assertTrue(engine.handback(second,"follow","out of scope"));
        assertThrows(IllegalStateException.class,() -> engine.handback(second,"follow","again")); assertEquals(1,engine.evaluateDue().size());
    }
    @Test void unreadFollowupReleasedOnFinishButAcknowledgedFollowupNotReleased() {
        String id = accept("a","a"); engine.receive(event("follow","a","alice",true,false));
        engine.report(id,engine.ticket(id).latestAssignment().id,new Report(ReportKind.READY,"done"));
        assertEquals(List.of("follow"),engine.evaluateDue().getFirst().triggerIds());
    }
    @Test void cursorAndStateRollbackTogetherAndSurviveReopen() {
        assertThrows(IllegalStateException.class,() -> store.atomic(() -> { engine.receive(event("a","a","alice",true,true),"cursor1"); throw new IllegalStateException("crash"); }));
        assertNull(engine.trigger("a")); assertNull(store.cursor("local"));
        engine.receive(event("a","a","alice",true,true),"cursor1"); store.close(); store = new SqliteStore(directory.resolve("state.db")); engine = new Engine(store,clock);
        assertNotNull(engine.trigger("a")); assertEquals("cursor1",store.cursor("local")); assertEquals(1,engine.evaluateDue().size());
    }
    @Test void effectsBackoffAndCrashRecoveryAreDurable() {
        accept("a","a"); Effect effect = store.claimEffects(clock.millis(),10).getFirst(); assertTrue(store.claimEffects(clock.millis(),10).isEmpty());
        store.failEffect(effect.id(),"offline",clock.millis()); assertTrue(store.claimEffects(clock.millis()+999,10).isEmpty());
        Effect retry = store.claimEffects(clock.millis()+1000,10).getFirst(); assertEquals(1,retry.attempts());
        store.recoverEffects(); assertEquals(1,store.claimEffects(clock.millis()+1000,10).size()); store.completeEffect(effect.id()); assertEquals(0,store.effectCount());
    }
    @Test void outboxReceiptPreventsDuplicateQueueAndRestrictsChannel() {
        String id = accept("a","a"); Channel channel = engine.ticket(id).channels.getFirst();
        assertTrue(engine.queueReply("reply1",id,channel,"done")); assertFalse(engine.queueReply("reply1",id,channel,"again"));
        engine.markReplySent("reply1","receipt1"); assertEquals("sent",engine.outboxMessage("reply1").status());
        assertThrows(IllegalArgumentException.class,() -> engine.queueReply("reply2",id,new Channel("local","other",Map.of()),"bad"));
    }
    @Test void policyVersionCannotBeReusedWithDifferentRules() {
        assertThrows(IllegalArgumentException.class,() -> engine.setPolicy(policy("v1",AcceptMode.AUTO)));
    }
    @Test void originalAdmissionBasisIsRevalidatedNotUpgradedByOwnSubscription() {
        engine.setPolicy(policy("v2",AcceptMode.AUTO));
        engine.receive(event("a","a","alice",true,false));
        String id = engine.decide(engine.evaluateDue().getFirst().id(),Outcome.accept("a","read",Set.of("fix-ci"))).ticketId();
        finish(id);
        SourcePolicy restrictive = new SourcePolicy(Set.of("alice"),Set.of(),Map.of("issue",new AdmissionRow(Admission.IGNORE,Admission.IGNORE,Admission.IGNORE)));
        engine.setPolicy(new Policy("v3",AcceptMode.AUTO,Map.of("local",restrictive),Set.of("fix-ci")));
        engine.receive(event("follow","a","alice",true,true)); assertEquals(TicketStatus.ENDED,engine.ticket(id).status);
    }
    @Test void attachRoutesExistingWorkAndUnsubscribePreservesReceivedOwnership() {
        String id = accept("a","a"); engine.receive(event("other","other","alice",true,true));
        engine.evaluateDue(); engine.attach(id,"other","same scope"); assertTrue(engine.evaluateDue().isEmpty());
        assertEquals("read files",engine.ticket(id).mandate.scope());
        engine.unsubscribe(new ThreadRef("local","other"));
        assertThrows(IllegalStateException.class,() -> engine.reevaluate("other"));
        assertTrue(engine.handback(id,"other","not my scope")); assertEquals(1,engine.evaluateDue().size());
    }
    @Test void batchCursorSupportsEmptyPagesAndRollsBackOnMixedSources() {
        engine.receiveBatch(List.of(),"local","empty-page"); assertEquals("empty-page",store.cursor("local"));
        Trigger wrong = new Trigger("wrong","other",List.of(new ThreadRef("other","a")),"alice",true,true,true,"issue",clock.instant().toString(),"body",null);
        assertThrows(IllegalArgumentException.class,() -> engine.receiveBatch(List.of(event("a","a","alice",true,true),wrong),"local","bad-page"));
        assertNull(engine.trigger("a")); assertEquals("empty-page",store.cursor("local"));
    }

    @Test void historicalPolicyVersionCannotBeReusedAfterChangingVersions() {
        engine.setPolicy(policy("v2",AcceptMode.AUTO));
        assertThrows(IllegalArgumentException.class,() -> engine.setPolicy(policy("v1",AcceptMode.OFF)));
        engine.setPolicy(policy("v1",AcceptMode.OBSERVE)); // 同一版本、相同内容的回滚可以审计。
    }
    @Test void expiredEvalCannotAcceptEvenWithoutSchedulerTick() {
        engine.receive(event("a","a","alice",true,true)); Evaluation evaluation = engine.evaluateDue().getFirst();
        engine.candidateDelivered(evaluation.id());
        Engine later = new Engine(store,Clock.offset(clock,Duration.ofMinutes(3)));
        assertEquals(DecisionKind.UNAVAILABLE,later.decide(evaluation.id(),Outcome.accept("a","read",Set.of())).kind());
        assertTrue(engine.tickets().isEmpty());
    }

}
