package io.github.dsh;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import io.github.dsh.runtime.WorkspaceAnalysisExecutor;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagementTest {
  @TempDir Path temporary;

  @Test
  void operatorAnswerContinuesWaitingAssignmentAndStoppedTicketRejectsAnswer() {
    try (SqliteStore store = new SqliteStore(temporary.resolve("db.sqlite"))) {
      Engine engine = new Engine(store);
      engine.setPolicy(
          new Policy(
              "v1",
              AcceptMode.OBSERVE,
              Map.of("local", new SourcePolicy(Set.of("owner"), Set.of(), Map.of())),
              Set.of()));
      engine.receive(
          new Trigger(
              "request",
              "local",
              List.of(new ThreadRef("local", "thread")),
              "owner",
              true,
              true,
              true,
              "direct",
              Clock.systemUTC().instant().toString(),
              "分析",
              new Channel("local", "thread", Map.of())));
      Decision decision =
          engine.decide(
              engine.evaluateDue().getFirst().id(), Outcome.accept("分析", "只读分析", Set.of()));
      Ticket ticket = engine.ticket(decision.ticketId());
      String assignment = ticket.latestAssignment().id;
      engine.assignmentStarted(ticket.id, assignment);
      engine.report(ticket.id, assignment, new Report(ReportKind.WAITING, "需要确认", "选择哪种行为？"));
      Ticket resumed = Manage.answer(engine, ticket.id, "请选择 A");
      assertEquals(assignment, resumed.latestAssignment().id);
      assertTrue(resumed.inputs.getLast().text.contains("请选择 A"));
      assertFalse(resumed.inputs.getLast().acknowledged);
      engine.stopTicket(ticket.id);
      assertThrows(IllegalStateException.class, () -> Manage.answer(engine, ticket.id, "不能恢复"));
    }
  }

  @Test
  void localExecutorReadsRealSourceButDoesNotFollowSymlink() throws Exception {
    Path workspace = Files.createDirectory(temporary.resolve("work"));
    Files.writeString(workspace.resolve("Real.java"), "class Real { /* TODO: test */ }\n");
    Path secret = temporary.resolve("Secret.java");
    Files.writeString(secret, "SECRET_MARKER");
    Files.createSymbolicLink(workspace.resolve("Escape.java"), secret);
    Ticket ticket = new Ticket();
    ticket.id = "test";
    ticket.mandate =
        new Mandate("v1", Strength.EXPLICIT, "只读", "local", "owner", "request", false, Set.of());
    Report report =
        new WorkspaceAnalysisExecutor(workspace).execute(ticket, new Assignment("a", "test", 0));
    assertEquals(ReportKind.READY, report.kind());
    assertTrue(report.summary().contains("1 个文件"));
    assertTrue(report.summary().contains("TODO"));
    assertFalse(report.summary().contains("SECRET_MARKER"));
  }
}
