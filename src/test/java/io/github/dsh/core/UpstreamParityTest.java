package io.github.dsh.core;

import static io.github.dsh.core.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** 对照 upstream docs/001-003 和 core/*.ts 的边界条件，而不只是检查方法存在。 */
class UpstreamParityTest {
  @TempDir Path directory;
  SqliteStore store;
  Engine engine;
  MutableClock clock = new MutableClock();

  static final class MutableClock extends Clock {
    long ms = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

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
      return Instant.ofEpochMilli(ms);
    }

    void advance(long millis) {
      ms += millis;
    }
  }

  @BeforeEach
  void open() {
    store = new SqliteStore(directory.resolve("state.db"));
    engine = new Engine(store, clock);
    engine.setPolicy(policy("v1", Set.of("alice")));
  }

  @AfterEach
  void close() {
    store.close();
  }

  Policy policy(String version, Set<String> actors) {
    return new Policy(
        version,
        AcceptMode.AUTO,
        Map.of("s", new SourcePolicy(actors, Set.of("ci"), Map.of("issue", AdmissionRow.safe()))),
        Set.of("fix"));
  }

  Trigger trigger(String id, String thread, String actor, boolean human, boolean addressed) {
    return new Trigger(
        id,
        "s",
        List.of(new ThreadRef("s", thread)),
        actor,
        human,
        addressed,
        true,
        "issue",
        clock.instant().toString(),
        "message " + id,
        new Channel("s", thread, Map.of("topic", thread)));
  }

  String accept(String id, String thread) {
    engine.receive(trigger(id, thread, "alice", true, true));
    return engine.decideTrigger(id, Outcome.accept("task", "repository", Set.of())).ticketId();
  }

  String running(String ticket) {
    String id = engine.ticket(ticket).latestAssignment().id;
    engine.assignmentStarted(ticket, id);
    return id;
  }

  void ready(String ticket) {
    String a = running(ticket);
    engine.report(ticket, a, new Report(ReportKind.READY, "done"));
  }

  Input lastInput(String ticket) {
    return engine.ticket(ticket).inputs.getLast();
  }

  void read(String ticket, String assignment, Input input) {
    engine.inputsDelivered(ticket, assignment, List.of(input.id));
    clock.advance(1);
    engine.inputsRead(ticket, assignment, List.of(input.id));
    clock.advance(1);
  }

  @Test
  void undeliveredCandidateNeverExpires() {
    engine.receive(trigger("a", "a", "alice", true, true));
    Evaluation evaluation = engine.evaluateDue().getFirst();
    clock.advance(1_000_000);
    assertEquals(evaluation.id(), engine.evaluateDue().getFirst().id());
    assertNull(engine.evaluationById(evaluation.id()).deliveredAt());
  }

  @Test
  void deliveryStartsTimeoutAndThreeExpiriesEndUnavailable() {
    engine.setProcessing(false, 100);
    engine.receive(trigger("a", "a", "alice", true, true));
    for (int n = 0; n < 3; n++) {
      Evaluation evaluation = engine.evaluateDue().getFirst();
      engine.candidateDelivered(evaluation.id());
      clock.advance(101);
      engine.evaluateDue();
    }
    assertTrue(engine.evaluateDue().isEmpty());
    assertEquals(DecisionKind.UNAVAILABLE, engine.decisions().getLast().kind());
  }

  @Test
  void pauseShiftsOnlyDeliveredDeadlineAndQueuesIncomingWork() {
    engine.setProcessing(false, 100);
    engine.receive(trigger("a", "a", "alice", true, true));
    Evaluation first = engine.evaluateDue().getFirst();
    engine.candidateDelivered(first.id());
    clock.advance(20);
    engine.setProcessing(true, 100);
    clock.advance(500);
    engine.receive(trigger("b", "b", "alice", true, true));
    assertTrue(engine.evaluateDue().isEmpty());
    engine.setProcessing(false, 100);
    List<Evaluation> evaluations = engine.evaluateDue();
    assertEquals(2, evaluations.size());
    assertEquals(first.deadline() + 500, engine.evaluationById(first.id()).deadline());
    clock.advance(81);
    engine.evaluateDue();
    assertNull(engine.evaluationById(first.id()));
  }

  @Test
  void pausedEndedTicketWaitsAndRevalidatesAtResumeTime() {
    String id = accept("a", "a");
    ready(id);
    engine.setProcessing(true, 100);
    engine.receive(trigger("follow", "a", "alice", true, false));
    assertEquals(TicketStatus.ENDED, engine.ticket(id).status);
    assertFalse(engine.ticket(id).undelivered.isEmpty());
    engine.setPolicy(policy("v2", Set.of()));
    engine.setProcessing(false, 100);
    engine.evaluateDue();
    assertEquals(TicketStatus.ENDED, engine.ticket(id).status);
    assertTrue(engine.ticket(id).undelivered.isEmpty());
    assertTrue(engine.triggerDisposition("follow").owners.isEmpty());
  }

  @Test
  void pausedResumeCreatesOneNewAssignmentAndResumeMarkerFirst() {
    String id = accept("a", "a");
    ready(id);
    engine.setProcessing(true, 100);
    engine.receive(trigger("f", "a", "alice", true, false));
    engine.setProcessing(false, 100);
    engine.evaluateDue();
    engine.evaluateDue();
    Ticket ticket = engine.ticket(id);
    assertEquals(2, ticket.assignments.size());
    String first = ticket.undelivered.getFirst();
    assertEquals(
        InputKind.RESUMED,
        ticket.inputs.stream().filter(i -> i.id.equals(first)).findFirst().orElseThrow().kind);
  }

  @Test
  void policyChangeImmediatelyDiscardsAndRequeuesRunningEval() {
    engine.receive(trigger("a", "a", "alice", true, true));
    Evaluation old = engine.evaluateDue().getFirst();
    engine.setPolicy(policy("v2", Set.of("alice")));
    assertNull(engine.evaluationById(old.id()));
    assertEquals(DecisionKind.UNAVAILABLE, engine.decisions().getLast().kind());
    assertEquals(List.of("a"), engine.evaluateDue().getFirst().triggerIds());
  }

  @Test
  void readyWaitsForOwnedPullRequestAndResultCanBePublishedBeforeMerge() {
    String id = accept("a", "a");
    engine.bindThread(id, new ThreadRef("s", "pr1"), true);
    ready(id);
    assertEquals(TicketStatus.OPEN, engine.ticket(id).status);
    assertEquals(new TicketStage("person", "review"), engine.ticketStage(id));
    assertEquals(
        "pending",
        engine.reply(new ReplyRequest(ReplyPurpose.RESULT, id, null, null, "PR ready")).status());
    clock.advance(10);
    engine.threadClosed(new ThreadRef("s", "pr1"), "merged", clock.instant().toString());
    assertEquals(TicketStatus.ENDED, engine.ticket(id).status);
    assertEquals("merged", engine.ticket(id).ended.outcome());
  }

  @Test
  void ownedThreadsTakePriorityOverContextualBoundThreads() {
    String id = accept("a", "a");
    engine.bindThread(id, new ThreadRef("s", "owned"), true);
    engine.bindThread(id, new ThreadRef("s", "other"), false);
    ready(id);
    engine.threadClosed(new ThreadRef("s", "owned"), "merged", clock.instant().toString());
    assertEquals(TicketStatus.ENDED, engine.ticket(id).status);
  }

  @Test
  void withoutOwnedThreadsAllBoundThreadsMustClose() {
    String id = accept("a", "a");
    engine.bindThread(id, new ThreadRef("s", "p1"), false);
    engine.bindThread(id, new ThreadRef("s", "p2"), false);
    ready(id);
    engine.threadClosed(new ThreadRef("s", "p1"), "closed", clock.instant().toString());
    assertEquals(TicketStatus.OPEN, engine.ticket(id).status);
    clock.advance(10);
    engine.threadClosed(new ThreadRef("s", "p2"), "merged", clock.instant().toString());
    assertEquals("merged", engine.ticket(id).ended.outcome());
  }

  @Test
  void consumedFollowupWithOnlyProgressDefersPrClosureUntilReady() {
    String id = accept("a", "a");
    engine.bindThread(id, new ThreadRef("s", "pr"), true);
    ready(id);
    clock.advance(10);
    engine.receive(trigger("f", "a", "alice", true, false));
    String assignment = engine.ticket(id).latestAssignment().id;
    Input input = lastInput(id);
    read(id, assignment, input);
    engine.acknowledge(id, "f");
    engine.report(id, assignment, new Report(ReportKind.PROGRESS, "working"));
    engine.threadClosed(new ThreadRef("s", "pr"), "merged", clock.instant().toString());
    assertEquals(TicketStatus.OPEN, engine.ticket(id).status);
    clock.advance(10);
    engine.report(id, assignment, new Report(ReportKind.READY, "finished followup"));
    assertEquals("merged", engine.ticket(id).ended.outcome());
  }

  @Test
  void delayedOldCloseDoesNotEndResumedNewWork() {
    String id = accept("a", "a");
    ready(id);
    long oldClose = clock.millis();
    clock.advance(100);
    engine.receive(trigger("f", "a", "alice", true, false));
    engine.bindThread(id, new ThreadRef("s", "pr"), true);
    engine.threadClosed(new ThreadRef("s", "pr"), "merged", oldClose);
    assertEquals(TicketStatus.OPEN, engine.ticket(id).status);
    ready(id);
    assertEquals(TicketStatus.ENDED, engine.ticket(id).status);
  }

  @Test
  void deliveredButUnreadHumanInputIsReleasedOnEnd() {
    String id = accept("a", "a");
    String assignment = running(id);
    engine.receive(trigger("f", "a", "alice", true, false));
    Input input = lastInput(id);
    engine.inputsDelivered(id, assignment, List.of(input.id));
    clock.advance(1);
    engine.report(id, assignment, new Report(ReportKind.READY, "done"));
    assertEquals(List.of("f"), engine.evaluateDue().getFirst().triggerIds());
  }

  @Test
  void readBeforeFinalReportIsNotReleasedButLaterArrivalIs() {
    String id = accept("a", "a");
    String assignment = running(id);
    engine.receive(trigger("f", "a", "alice", true, false));
    read(id, assignment, lastInput(id));
    engine.receive(trigger("late", "a", "alice", true, false));
    engine.report(id, assignment, new Report(ReportKind.READY, "done"));
    assertEquals(List.of("late"), engine.evaluateDue().getFirst().triggerIds());
  }

  @Test
  void failedWorkerHoldsAutomatedInputsButHumanFollowupReturnsToWork() {
    String id = accept("a", "a");
    String assignment = running(id);
    engine.report(id, assignment, new Report(ReportKind.FAILED, "error"));
    engine.receive(trigger("ci", "a", "ci", false, false));
    assertTrue(lastInput(id).held);
    engine.note(id, "automatic context");
    assertTrue(lastInput(id).held);
    assertTrue(engine.executionHeld(id));
    engine.receive(trigger("f", "a", "alice", true, false));
    assertFalse(engine.executionHeld(id));
    assertFalse(lastInput(id).held);
    assertEquals(AssignmentStatus.RUNNING, engine.ticket(id).latestAssignment().status);
    assertEquals("working", engine.ticketStage(id).stage());
  }

  @Test
  void contextOnlyHumanDoesNotReleaseBlockedWorker() {
    String id = accept("a", "a");
    String assignment = running(id);
    engine.followForContext(id, new ThreadRef("s", "context"));
    engine.report(id, assignment, new Report(ReportKind.BLOCKED, "needs decision"));
    engine.receive(trigger("context-human", "context", "alice", true, false));
    assertTrue(engine.executionHeld(id));
  }

  @Test
  void retryRestoresDeliveredUnreadInputAndDeliveryFailureIsRetryable() {
    String id = accept("a", "a");
    String assignment = running(id);
    engine.receive(trigger("f", "a", "alice", true, false));
    Input input = lastInput(id);
    engine.inputsDelivered(id, assignment, List.of(input.id));
    engine.deliveryFailed(id, assignment, "offline");
    engine.retryTicket(id);
    Ticket ticket = engine.ticket(id);
    assertTrue(ticket.undelivered.contains(input.id));
    assertNotEquals(assignment, ticket.latestAssignment().id);
    assertNull(ticket.delivery);
  }

  @Test
  void interruptionRestartsBusyAssignmentWithRecoveryNote() {
    String id = accept("a", "a");
    String assignment = running(id);
    engine.receive(trigger("f", "a", "alice", true, false));
    Input input = lastInput(id);
    engine.inputsDelivered(id, assignment, List.of(input.id));
    engine.assignmentInterrupted(id, assignment);
    Ticket ticket = engine.ticket(id);
    assertEquals(2, ticket.assignments.size());
    assertTrue(ticket.undelivered.contains(input.id));
    assertTrue(
        ticket.inputs.stream()
            .anyMatch(i -> i.text.contains("Do not repeat completed external operations")));
  }

  @Test
  void settledWaitingAssignmentRetainsSessionOnRestart() {
    String id = accept("a", "a");
    String assignment = running(id);
    clock.advance(10);
    engine.report(id, assignment, new Report(ReportKind.WAITING, "idle"));
    engine.assignmentInterrupted(id, assignment);
    assertEquals(1, engine.ticket(id).assignments.size());
    assertEquals(assignment, engine.ticket(id).latestAssignment().id);
    assertTrue(engine.requestStatus(id));
    assertEquals(InputKind.TEXT, lastInput(id).kind);
  }

  @Test
  void questionKeepClearAndReplacementWithdrawUnsentAttentionReplies() {
    String id = accept("a", "a");
    String a = running(id);
    engine.report(
        id, a, new Report(ReportKind.WAITING, "question", "Which branch?", true, "a", null));
    Reply reply =
        engine.reply(
            new ReplyRequest(ReplyPurpose.ATTENTION, id, null, "Which branch?", "Which branch?"));
    engine.report(id, a, new Report(ReportKind.PROGRESS, "checking"));
    assertNotNull(engine.ticket(id).attention);
    engine.report(id, a, Report.clearQuestion(ReportKind.WAITING, "answered"));
    assertNull(engine.ticket(id).attention);
    assertEquals("dropped", engine.outboxMessage(reply.id()).status());
  }

  @Test
  void questionAndAnswerReferencesMustBeHumanAndReached() {
    String id = accept("a", "a");
    String a = running(id);
    engine.receive(trigger("bot", "a", "ci", false, false));
    engine.receive(trigger("other", "other", "alice", true, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> engine.report(id, a, new Report(ReportKind.READY, "done", "question")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            engine.report(
                id, a, new Report(ReportKind.WAITING, "x", "question", true, "bot", null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            engine.report(id, a, new Report(ReportKind.WAITING, "x", null, false, null, "other")));
  }

  @Test
  void answeredReportAuthorizesOneAnswerAndRoutesToQuestionChannel() {
    String id = accept("a", "a");
    String a = running(id);
    engine.receive(trigger("f", "a", "alice", true, false));
    assertThrows(
        IllegalStateException.class,
        () -> engine.reply(new ReplyRequest(ReplyPurpose.ANSWER, id, "f", null, "answer")));
    engine.report(id, a, new Report(ReportKind.WAITING, "answered", null, false, null, "f"));
    Reply answer = engine.reply(new ReplyRequest(ReplyPurpose.ANSWER, id, "f", null, "answer"));
    assertEquals("a", engine.outboxMessage(answer.id()).channel().thread());
    assertTrue(
        engine.reply(new ReplyRequest(ReplyPurpose.ANSWER, id, "f", null, "answer")).reused());
    assertThrows(
        IllegalStateException.class,
        () ->
            engine.reply(new ReplyRequest(ReplyPurpose.ANSWER, id, "f", null, "different answer")));
  }

  @Test
  void contextSubscriptionCannotAcknowledgeAndProtectedThreadsCannotBeDropped() {
    String id = accept("a", "a");
    engine.followForContext(id, new ThreadRef("s", "context"));
    engine.receive(trigger("c", "context", "alice", true, false));
    assertThrows(IllegalArgumentException.class, () -> engine.acknowledge(id, "c"));
    assertThrows(
        IllegalArgumentException.class, () -> engine.markUnrelated(id, "s:a", "unrelated"));
    engine.bindThread(id, new ThreadRef("s", "pr"), true);
    assertThrows(
        IllegalArgumentException.class, () -> engine.markUnrelated(id, "s:pr", "unrelated"));
  }

  @Test
  void unrelatedAttachedThreadHandsBackItsOwnedMessages() {
    String id = accept("a", "a");
    engine.receive(trigger("f", "other", "alice", true, true));
    engine.attach(id, "f", "same job");
    engine.markUnrelated(id, "s:other", "wrong job");
    assertEquals(List.of("f"), engine.evaluateDue().getFirst().triggerIds());
    assertEquals("wrong job", engine.candidate("f").handbacks().get("f").get(id));
  }

  @Test
  void panelCanReleaseOwnedFollowupButModelCannotStealIt() {
    String id = accept("a", "a");
    engine.receive(trigger("f", "a", "alice", true, false));
    assertThrows(IllegalStateException.class, () -> engine.reevaluate("f"));
    assertThrows(
        IllegalStateException.class, () -> engine.decideTrigger("f", Outcome.ignore("ignore")));
    assertTrue(engine.sendFollowupToEval("f"));
    engine.evaluateDue();
    engine.attach(id, "f", "person reassigned");
    assertTrue(engine.evaluateDue().isEmpty());
  }

  @Test
  void directStopDoesNotDiscardDifferentActiveThreadEval() {
    String id = accept("a", "a");
    engine.receive(trigger("ci", "a", "ci", false, true));
    Evaluation active = engine.evaluateDue().getFirst();
    engine.receive(trigger("stop", "a", "alice", true, false));
    Decision stopped =
        engine.decideTrigger(
            "stop", new Outcome(DecisionKind.STOP, null, null, Set.of(), "human stop", id));
    assertEquals(DecisionKind.STOP, stopped.kind());
    assertNotNull(engine.evaluationById(active.id()));
    assertTrue(engine.ticket(id).stopped);
  }

  @Test
  void explicitFollowupMandateRemainsValidWhenSenderRemoved() {
    String first = accept("a", "a");
    engine.receive(trigger("f", "a", "alice", true, false));
    engine.handback(first, "f", "new scope");
    String second =
        engine.decideTrigger("f", Outcome.accept("new task", "other scope", Set.of())).ticketId();
    ready(second);
    engine.setPolicy(policy("v2", Set.of()));
    engine.receive(trigger("again", "a", "other", true, false));
    assertEquals(TicketStatus.OPEN, engine.ticket(second).status);
  }

  @Test
  void sourceScopeRevocationPreventsResumeAndSelfMessagesAreFiltered() {
    String id = accept("a", "a");
    ready(id);
    engine.configureSourceScope("s", new SourceScope(true, Set.of("issue"), "bot-self"));
    assertEquals("filtered", engine.receive(trigger("self", "a", "bot-self", false, true)));
    engine.configureSourceScope("s", new SourceScope(false, Set.of(), "bot-self"));
    assertEquals("filtered", engine.receive(trigger("f", "a", "alice", true, true)));
    assertEquals(TicketStatus.ENDED, engine.ticket(id).status);
  }

  @Test
  void senderlessCiUsesAdmissionRowAndDirectUnlistedNeverUsesFollowup() {
    engine.setPolicy(
        new Policy(
            "v2",
            AcceptMode.AUTO,
            Map.of(
                "s",
                new SourcePolicy(
                    Set.of("alice"),
                    Set.of(),
                    Map.of(
                        "issue",
                        new AdmissionRow(
                            Admission.INFERRED, Admission.IGNORE, Admission.INFERRED)))),
            Set.of("fix")));
    engine.receive(trigger("ci", "ci", "", false, false));
    assertEquals(Strength.INFERRED, engine.evaluateDue().getFirst().strength());
    String id = accept("a", "a");
    engine.receive(
        new Trigger(
            "dm",
            "s",
            List.of(new ThreadRef("s", "a")),
            "outsider",
            true,
            true,
            true,
            "issue",
            clock.instant().toString(),
            "direct",
            null,
            Map.of("direct", true),
            null,
            null));
    engine.handback(id, "dm", "new work");
    assertThrows(
        IllegalArgumentException.class,
        () -> engine.decideTrigger("dm", Outcome.accept("new", "scope", Set.of())));
  }

  @Test
  void githubIdentityComparisonIsOptInAndFeishuRemainsCaseSensitive() {
    engine.setPolicy(
        new Policy(
            "v2",
            AcceptMode.AUTO,
            Map.of("s", new SourcePolicy(Set.of("ALICE"), Set.of("CI"), Map.of(), true)),
            Set.of("fix")));
    engine.receive(trigger("a", "a", "alice", true, true));
    assertEquals(Strength.EXPLICIT, engine.evaluateDue().getFirst().strength());
    engine.setPolicy(
        new Policy(
            "v3",
            AcceptMode.AUTO,
            Map.of("s", new SourcePolicy(Set.of("ALICE"), Set.of(), Map.of(), false)),
            Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> engine.decideTrigger("a", Outcome.accept("x", "y", Set.of())));
  }

  @Test
  void staleEffectLeaseCannotCompleteOrFailNewGeneration() {
    accept("a", "a");
    Effect first = store.claimEffects(clock.millis(), 1, Set.of(EffectKind.START), 100).getFirst();
    clock.advance(101);
    Effect second = store.claimEffects(clock.millis(), 1, Set.of(EffectKind.START), 100).getFirst();
    assertTrue(second.generation() > first.generation());
    assertFalse(store.completeEffect(first.id(), first.generation()));
    assertFalse(
        store.failEffect(first.id(), first.generation(), "late", clock.millis(), 2000, 60000));
    assertTrue(store.completeEffect(second.id(), second.generation()));
  }

  @Test
  void sourceAndDomainAuditRollbackWithState() {
    long count = engine.events(0, 1000).size();
    assertThrows(
        IllegalStateException.class,
        () ->
            store.atomic(
                () -> {
                  engine.receive(trigger("a", "a", "alice", true, true));
                  store.putState("source", "cursor", "new");
                  throw new IllegalStateException("fail");
                }));
    assertNull(engine.trigger("a"));
    assertNull(store.getState("source", "cursor", String.class));
    assertEquals(count, engine.events(0, 1000).size());
  }

  @Test
  void unavailableCanBeDismissedAndAuditAndMetadataPersist() {
    engine.receive(trigger("a", "a", "alice", true, true));
    Evaluation e = engine.evaluateDue().getFirst();
    Decision decision =
        engine.decide(
            e.id(),
            new Outcome(
                DecisionKind.UNAVAILABLE,
                null,
                null,
                Set.of(),
                "offline",
                null,
                null,
                null,
                null,
                Map.of("intent", "unknown")));
    assertTrue(engine.dismiss(decision.id()).dismissed());
    assertEquals("unknown", engine.dismiss(decision.id()).audit().get("intent"));
    engine.describeThread("s:a", Map.of("label", "Issue A"));
    assertEquals("Issue A", engine.threads().getFirst().metadata.get("label"));
  }

  @Test
  void unrelatedPolicyChangesDoNotWakeTicketsButMatchedInstructionChangesDo() {
    String explicit = accept("a", "a");
    int before = engine.ticket(explicit).inputs.size();
    engine.setPolicy(
        new Policy(
            "v2",
            AcceptMode.AUTO,
            Map.of("s", new SourcePolicy(Set.of("alice", "bob"), Set.of("ci"), Map.of())),
            Set.of("fix"),
            Map.of("fix", "initial")));
    assertEquals(before, engine.ticket(explicit).inputs.size());
    engine.receive(trigger("inferred", "other", "alice", true, false));
    String inferred =
        engine
            .decideTrigger("inferred", Outcome.accept("fix", "repository", Set.of("fix")))
            .ticketId();
    int inferredBefore = engine.ticket(inferred).inputs.size();
    engine.setPolicy(
        new Policy(
            "v3",
            AcceptMode.AUTO,
            Map.of("s", new SourcePolicy(Set.of("alice", "bob"), Set.of("ci"), Map.of())),
            Set.of("fix"),
            Map.of("fix", "new instruction")));
    assertEquals(before, engine.ticket(explicit).inputs.size());
    assertEquals(inferredBefore + 1, engine.ticket(inferred).inputs.size());
    assertEquals(InputKind.POLICY_CHANGED, lastInput(inferred).kind);
  }

  @Test
  void newestAdmissibleSourceTimeWinsEvenWhenPollingArrivesOutOfOrder() {
    Trigger newer =
        new Trigger(
            "new",
            "s",
            List.of(new ThreadRef("s", "a")),
            "alice",
            true,
            true,
            true,
            "issue",
            "2026-01-01T01:00:00+00:00",
            "new",
            null);
    Trigger older =
        new Trigger(
            "old",
            "s",
            List.of(new ThreadRef("s", "a")),
            "alice",
            true,
            true,
            true,
            "issue",
            "2026-01-01T00:00:00Z",
            "old",
            null);
    engine.receive(newer);
    engine.receive(older);
    assertEquals("new", engine.evaluateDue().getFirst().primaryTriggerId());
    assertEquals("2026-01-01T01:00:00.000Z", engine.trigger("new").at());
  }

  @Test
  void sourceScopeChangeDiscardsInFlightEvalBeforeItCanAccept() {
    engine.receive(trigger("a", "a", "alice", true, true));
    Evaluation old = engine.evaluateDue().getFirst();
    engine.configureSourceScope("s", new SourceScope(false, Set.of(), null));
    assertNull(engine.evaluationById(old.id()));
    assertEquals(
        DecisionKind.UNAVAILABLE,
        engine.decide(old.id(), Outcome.accept("x", "y", Set.of())).kind());
    assertTrue(engine.evaluateDue().isEmpty());
    assertTrue(engine.tickets().isEmpty());
  }

  @Test
  void nestedCommandFailureRollsBackToSavepointEvenWhenOuterCallerCatchesIt() {
    store.atomic(
        () -> {
          store.putState("test", "before", "saved");
          assertThrows(
              IllegalStateException.class,
              () ->
                  store.atomic(
                      () -> {
                        engine.receive(trigger("a", "a", "alice", true, true));
                        throw new IllegalStateException("fail inner");
                      }));
          store.putState("test", "after", "saved");
          return null;
        });
    assertNull(engine.trigger("a"));
    assertEquals("saved", store.getState("test", "before", String.class));
    assertEquals("saved", store.getState("test", "after", String.class));
  }

  @Test
  void evaluationKeepsOriginalAdmissionBasisWhenAnotherTicketAppearsDuringAssessment() {
    engine.receive(trigger("inferred", "a", "alice", true, false));
    Evaluation eval = engine.evaluateDue().getFirst();
    String explicit = accept("direct", "a");
    assertNotNull(explicit);
    String id =
        engine
            .decide(eval.id(), Outcome.accept("inferred task", "scope", Set.of("fix")))
            .ticketId();
    assertEquals("admission", engine.ticket(id).mandate.basis());
    assertFalse(engine.ticket(id).mandate.followupBasis());
  }

  @Test
  void replayOfAlreadyAcceptedTriggerRecordsIgnoreInsteadOfCreatingSecondTicket() {
    accept("a", "a");
    assertTrue(engine.reevaluate("a"));
    assertTrue(engine.evaluateDue().isEmpty());
    assertEquals(1, engine.tickets().size());
    assertEquals(DecisionKind.IGNORE, engine.decisions().getLast().kind());
  }

  @Test
  void attachingOneMessageOfBatchRequeuesOtherMessagesWithUnavailableAudit() {
    String id = accept("a", "a");
    engine.receive(trigger("b", "other", "alice", true, true));
    engine.receive(trigger("c", "other", "alice", true, true));
    engine.evaluateDue();
    engine.attach(id, "b", "related");
    assertEquals(DecisionKind.UNAVAILABLE, engine.decisions().getLast().kind());
    assertEquals(List.of("c"), engine.evaluateDue().getFirst().triggerIds());
  }

  @Test
  void timelineCursorPreservesMessagesSharingTimestamp() {
    engine.receive(trigger("a", "same", "alice", true, true));
    engine.receive(trigger("b", "same", "alice", true, true));
    engine.receive(trigger("c", "same", "alice", true, true));
    TimelinePage first = engine.threadTimeline("s:same", null, 2);
    assertEquals(List.of("a", "b"), first.items().stream().map(Trigger::id).toList());
    TimelinePage second = engine.threadTimeline("s:same", first.nextCursor(), 2);
    assertEquals(List.of("c"), second.items().stream().map(Trigger::id).toList());
    assertNull(second.nextCursor());
  }

  @Test
  void coalescedEffectPreservesLatestPayloadAgainstStaleCompletion() {
    store.enqueue(EffectKind.NOTICE, "ticket", Map.of("revision", "1"), clock.millis());
    Effect first = store.claimEffects(clock.millis(), 1).getFirst();
    store.coalesceEffect(EffectKind.NOTICE, "ticket", Map.of("revision", "2"), clock.millis());
    assertFalse(store.completeEffect(first.id(), first.generation()));
    Effect second = store.claimEffects(clock.millis(), 1).getFirst();
    assertEquals("2", second.payload().get("revision"));
    assertTrue(store.completeEffect(second.id(), second.generation()));
  }

  @Test
  void schemaOneMigrationReconstructsEndAndPendingInputFacts() throws Exception {
    String id = accept("a", "a");
    Ticket ticket = engine.ticket(id);
    ticket.inputs.forEach(
        input -> {
          input.at = 0;
          input.human = false;
        });
    ticket.undelivered.clear();
    store.save("ticket", id, ticket);
    store.close();
    try (java.sql.Connection connection =
            java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("state.db"));
        java.sql.Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA user_version=1");
    }
    store = new SqliteStore(directory.resolve("state.db"));
    engine = new Engine(store, clock);
    Ticket migrated = engine.ticket(id);
    assertTrue(migrated.inputs.getFirst().human);
    assertTrue(migrated.inputs.getFirst().at > 0);
    assertTrue(migrated.undelivered.contains(migrated.inputs.getFirst().id));
  }

  @Test
  void acceptedRuleSnapshotAndChangedRuleTextRemainAvailableToWorker() {
    Policy base = engine.policy();
    engine.setPolicy(
        new Policy(
            "rules1",
            AcceptMode.AUTO,
            base.sources(),
            Set.of("fix"),
            Map.of("fix", "Do not acknowledge; inspect quietly.")));
    engine.receive(trigger("rule", "rule", "alice", true, false));
    Evaluation evaluation = engine.evaluateDue().getFirst();
    String id =
        engine
            .decide(evaluation.id(), Outcome.accept("Fix", "one module", Set.of("fix")))
            .ticketId();
    assertEquals(
        "Do not acknowledge; inspect quietly.",
        engine.ticket(id).matchedRuleInstructions.get("fix"));
    engine.setPolicy(
        new Policy(
            "rules2",
            AcceptMode.AUTO,
            base.sources(),
            Set.of("fix"),
            Map.of("fix", "Report only the final result.")));
    Ticket ticket = engine.ticket(id);
    assertEquals("Do not acknowledge; inspect quietly.", ticket.matchedRuleInstructions.get("fix"));
    assertTrue(
        ticket.inputs.stream()
            .anyMatch(
                i ->
                    i.kind == InputKind.POLICY_CHANGED
                        && i.text.contains("Report only the final result.")));
    assertTrue(engine.listOutbox().isEmpty());
  }

  @Test
  void sourceKindScopeControlsDynamicRowObservedAndResumeRevalidation() {
    engine.registerSourceScope("s", t -> new ScopeDecision(true, true, "issue", null));
    engine.receive(trigger("plugin", "plugin", "alice", true, false));
    assertEquals(Strength.INFERRED, engine.admissionStrength("plugin"));
    Evaluation evaluation = engine.evaluateDue().getFirst();
    String id =
        engine
            .decide(evaluation.id(), Outcome.accept("Fix", "one module", Set.of("fix")))
            .ticketId();
    engine.registerSourceScope(
        "s", t -> new ScopeDecision(false, false, "issue", "pluginNotListening"));
    assertTrue(engine.ticket(id).inputs.stream().anyMatch(i -> i.kind == InputKind.POLICY_CHANGED));
    assertEquals("filtered", engine.receive(trigger("filtered", "filtered", "alice", true, true)));
    assertEquals("pluginNotListening", engine.triggerDisposition("filtered").filtered);
    engine.registerSourceScope("s", t -> new ScopeDecision(true, false, "issue", null));
    engine.receive(trigger("not-observed", "other", "alice", true, false));
    assertNull(engine.admissionStrength("not-observed"));
    engine.receive(trigger("explicit-plugin", "explicit-plugin", "alice", true, true));
    assertEquals(Strength.EXPLICIT, engine.admissionStrength("explicit-plugin"));
  }
}
