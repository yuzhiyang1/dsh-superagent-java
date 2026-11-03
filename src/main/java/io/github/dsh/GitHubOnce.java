package io.github.dsh;

import io.github.dsh.adapters.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import io.github.dsh.runtime.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** 显式运行一次 GitHub 评论接入与文本执行。不开守护进程，不隐式启用外部写入。 */
public final class GitHubOnce {
  private GitHubOnce() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2)
      throw new IllegalArgumentException("Usage: GitHubOnce owner/repository issue-number");
    String repository = args[0];
    int issue = Integer.parseInt(args[1]);
    String source = "github-" + repository.replace('/', '-') + "-" + issue;
    boolean write = "true".equals(System.getenv("DSH_ENABLE_WRITES"));
    String allowed = required("DSH_ALLOWED_ACTORS");
    GitHubAdapter github =
        new GitHubAdapter(
            URI.create("https://api.github.com/"),
            required("GITHUB_TOKEN"),
            source,
            required("DSH_BOT_LOGIN"),
            write);
    OpenAiExecutor model =
        new OpenAiExecutor(
            URI.create(required("OPENAI_BASE_URL")),
            required("OPENAI_API_KEY"),
            required("OPENAI_MODEL"));
    Clock clock = Clock.systemUTC();
    try (SqliteStore store =
        new SqliteStore(Path.of(System.getenv().getOrDefault("DSH_DB", "state/github.sqlite")))) {
      Engine engine = new Engine(store, clock);
      // 版本由操作人明确维护；变更名单必须更换版本，旧版本不能悄悄复用。
      engine.setPolicy(
          new Policy(
              required("DSH_POLICY_VERSION"),
              AcceptMode.OBSERVE,
              Map.of(
                  source,
                  new SourcePolicy(
                      new HashSet<>(
                          Arrays.stream(allowed.split(","))
                              .map(String::strip)
                              .filter(s -> !s.isEmpty())
                              .toList()),
                      Set.of(),
                      Map.of("comments", AdmissionRow.safe()))),
              Set.of("text-analysis")));
      String cursor = store.cursor(source);
      GitHubAdapter.PollResult batch =
          github.readIssueComments(
              repository, issue, cursor == null ? null : Instant.parse(cursor));
      SourceBridge.receiveBatch(
          engine, batch.events(), source, batch.cursor().toString(), "comments", true);
      ModelAssessor assessor = new ModelAssessor(model);
      for (Evaluation evaluation : engine.evaluateDue()) {
        Outcome outcome;
        try {
          outcome = assessor.assess(engine, evaluation);
        } catch (Exception failure) {
          outcome =
              new Outcome(
                  DecisionKind.UNAVAILABLE,
                  null,
                  null,
                  Set.of(),
                  "Assessment failed: " + failure.getClass().getSimpleName(),
                  null);
        }
        System.out.println(engine.decide(evaluation.id(), outcome));
      }
      ChannelSender sender = new AdapterChannelSender(Map.of(source, github), Map.of());
      OrchestratorRuntime runtime =
          new OrchestratorRuntime(engine, store, new OpenAiTextExecutor(model), sender, clock);
      runtime.drain(100);
      for (Ticket ticket : engine.tickets()) {
        Assignment assignment = ticket.latestAssignment();
        System.out.println(
            ticket.id
                + " "
                + ticket.status
                + " "
                + (assignment.report == null ? "" : assignment.report.summary()));
      }
      System.out.println("待处理 effects: " + store.effectCount() + "; 外部写入: " + write);
      if (!write)
        System.out.println(
            "回复留在 Outbox，不发送。审核后显式 DSH_ENABLE_WRITES=true 再运行可发送。\n"
                + "默认仅观察推断候选；白名单人的明确请求仍可执行文本分析并调用模型 API。");
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Missing environment variable: " + name);
    return value;
  }
}
