package io.github.dsh;

import io.github.dsh.adapters.CodingExecutor;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import io.github.dsh.runtime.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** 一次有界编码工作；只改 ticket 副本，不执行 shell、不自动 commit/push。 */
public final class CodeOnce {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Usage: CodeOnce DB TEMPLATE WORKSPACES [--allow-file-writes] [--request task text]");
        boolean writes = false; String task = null;
        for (int i = 3; i < args.length; i++) {
            if ("--allow-file-writes".equals(args[i])) writes = true;
            else if ("--request".equals(args[i]) && i + 1 < args.length) { task = String.join(" ", Arrays.copyOfRange(args, i + 1, args.length)); break; }
            else throw new IllegalArgumentException("Unknown option: " + args[i]);
        }
        Clock clock = Clock.systemUTC();
        CodingExecutor executor = new CodingExecutor(URI.create(required("OPENAI_BASE_URL")), required("OPENAI_API_KEY"),
                required("OPENAI_MODEL"), Path.of(args[1]), Path.of(args[2]), writes, 12);
        try (SqliteStore store = new SqliteStore(Path.of(args[0]))) {
            Engine engine = new Engine(store, clock);
            Policy localPolicy = new Policy("local-coding-v1", AcceptMode.OBSERVE,
                    Map.of("local-code", new SourcePolicy(Set.of("operator"), Set.of(), Map.of("direct", AdmissionRow.safe()))), Set.of());
            if (!engine.policy().sources().isEmpty() && !engine.policy().equals(localPolicy))
                throw new IllegalStateException("Use a dedicated coding database; this database has a different policy");
            engine.setPolicy(localPolicy);
            if (task != null) {
                String request = "local-code-" + UUID.randomUUID();
                engine.receive(new Trigger(request, "local-code", List.of(new ThreadRef("local-code", request)), "operator",
                        true, true, false, "direct", clock.instant().toString(), task,
                        new Channel("local-code", request, Map.of())));
                for (Evaluation evaluation : engine.evaluateDue()) {
                    engine.decide(evaluation.id(), Outcome.accept("本机编码请求", "仅在分配的独立副本内读取和修改允许的源码文件；禁止 shell、网络工具、提交与发布；请求内容：" + task, Set.of()));
                }
            }
            OrchestratorRuntime runtime = new OrchestratorRuntime(engine, store, executor, message -> {
                System.out.println(message.text()); return "local:" + message.id();
            }, clock);
            runtime.drain(100);
            for (Ticket ticket : engine.tickets()) {
                Assignment assignment = ticket.latestAssignment();
                System.out.println("Ticket: " + ticket.id + " " + ticket.status + "; 副本: " + executor.workspace(ticket.id));
                if (assignment.report != null) {
                    System.out.println(assignment.report.kind() + ": " + assignment.report.summary());
                    if (assignment.report.question() != null) System.out.println("等待人工：" + assignment.report.question());
                }
            }
            System.out.println("原始模板未修改；副本须人工审查和运行测试。待处理 effects: " + store.effectCount());
        }
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + key);
        return value;
    }
}
