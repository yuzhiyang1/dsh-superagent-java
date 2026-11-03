package io.github.dsh;

import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import io.github.dsh.runtime.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** 从 main 开始读：无密钥、无外部写入，使用真实 SQLite、Engine、执行器和 Outbox。 */
public final class Demo {
  public static void main(String[] args) throws Exception {
    Path home = Files.createTempDirectory("dsh-java-demo-");
    Path workspace = Files.createDirectory(home.resolve("workspace"));
    Files.writeString(
        workspace.resolve("Greeting.java"),
        """
        public class Greeting {
            // TODO: 为问候方法添加单元测试
            public String hello(String name) { return "Hello " + name; }
        }
        """);
    Path database = home.resolve("orchestrator.sqlite");
    Clock clock = Clock.systemUTC();
    String ticketId;
    try (SqliteStore store = new SqliteStore(database)) {
      Engine engine = new Engine(store, clock);
      engine.setPolicy(
          new Policy(
              "demo-v1",
              AcceptMode.OBSERVE,
              Map.of(
                  "local",
                  new SourcePolicy(
                      Set.of("learner"), Set.of(), Map.of("comments", AdmissionRow.safe()))),
              Set.of("java-review")));
      OrchestratorRuntime runtime = runtime(engine, store, workspace, clock);

      System.out.println("1. 明确请求 → Eval → Mandate → Ticket → Assignment → 真实只读源码分析");
      Trigger request = trigger("request-1", true, "请检查 Java 源码结构和 TODO", clock);
      engine.receive(request);
      Evaluation evaluation = engine.evaluateDue().getFirst();
      Decision decision =
          engine.decide(
              evaluation.id(), Outcome.accept("检查 Java 源码", "只读分析已配置工作目录的 Java 文件", Set.of()));
      ticketId = decision.ticketId();
      runtime.drain(100);
      require(engine.ticket(ticketId).status == TicketStatus.ENDED, "工单应完成");

      System.out.println("2. 重复事件不重复建单；普通人的后续消息在原 Mandate 下恢复同一工单");
      engine.receive(request);
      require(engine.tickets().size() == 1, "事件应幂等");
      engine.receive(trigger("followup-1", false, "请再检查一次", clock));
      require(engine.ticket(ticketId).status == TicketStatus.OPEN, "后续消息应恢复");
      runtime.drain(100);
      require(engine.ticket(ticketId).assignments.size() == 2, "恢复应创建新 Assignment");

      System.out.println("3. observe 只记录推断候选，不让推断授权自动执行");
      Trigger inferred =
          new Trigger(
              "inferred-1",
              "local",
              List.of(new ThreadRef("local", "other")),
              "learner",
              true,
              false,
              true,
              "comments",
              clock.instant().toString(),
              "可以看看这些 TODO",
              channel("other"));
      engine.receive(inferred);
      Evaluation candidate = engine.evaluateDue().getFirst();
      Decision observed =
          engine.decide(
              candidate.id(),
              new Outcome(
                  DecisionKind.IGNORE,
                  null,
                  null,
                  Set.of("java-review"),
                  "observed",
                  null,
                  null,
                  null,
                  null,
                  Map.of("would_accept", true)));
      require(observed.kind() != DecisionKind.ACCEPT, "observe 不能接受推断任务");

      System.out.println("4. 人工 stop 撤回授权：后续消息不能恢复旧工单");
      engine.receive(trigger("before-stop", true, "再检查一次，稍后可能取消", clock));
      engine.stopTicket(ticketId);
      engine.receive(trigger("after-stop", false, "继续？", clock));
      require(engine.ticket(ticketId).stopped, "stop 应永久阻止该授权恢复");
      runtime.drain(100);
    }
    try (SqliteStore store = new SqliteStore(database)) {
      Engine restarted = new Engine(store, clock);
      require(restarted.ticket(ticketId).stopped, "重启必须保留状态");
      System.out.println("5. 重开 SQLite：工单、Assignment、授权和回执仍在。");
      System.out.println("演示通过。数据库保留在：" + database);
    }
  }

  private static OrchestratorRuntime runtime(
      Engine engine, SqliteStore store, Path workspace, Clock clock) throws Exception {
    return new OrchestratorRuntime(
        engine,
        store,
        new WorkspaceAnalysisExecutor(workspace),
        message -> {
          System.out.println("[本地 Channel] " + message.text());
          return "console:" + message.id();
        },
        clock);
  }

  private static Trigger trigger(String id, boolean addressed, String text, Clock clock) {
    return new Trigger(
        id,
        "local",
        List.of(new ThreadRef("local", "learning")),
        "learner",
        true,
        addressed,
        true,
        "comments",
        clock.instant().toString(),
        text,
        channel("learning"));
  }

  private static Channel channel(String thread) {
    return new Channel("local", thread, Map.of());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
