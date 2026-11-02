package io.github.dsh.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.dsh.core.Model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** 真工具循环 fixture：模型响应是脚本，文件读写与 HTTP 是真实实现。 */
class CodingExecutorTest {
    @TempDir Path temp;
    HttpServer server; URI base; Path template, workspaces;
    final List<JsonNode> requests = new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        template = Files.createDirectories(temp.resolve("source")); workspaces = temp.resolve("workers");
        Files.writeString(template.resolve("Hello.java"), "class Hello {}\n");
        Files.writeString(template.resolve(".env"), "SECRET=never-copy");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }
    @AfterEach void stop() { server.stop(0); }
    void model(Function<Integer, Map<String,Object>> next) {
        AtomicInteger round = new AtomicInteger();
        server.createContext("/chat/completions", exchange -> {
            requests.add(HttpJsonTransport.JSON.readTree(exchange.getRequestBody()));
            byte[] body = HttpJsonTransport.JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", next.apply(round.getAndIncrement())))));
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
    }
    static Map<String,Object> tool(String name, Map<String,String> arguments) {
        try { return Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", UUID.randomUUID().toString(), "type", "function",
                "function", Map.of("name", name, "arguments", HttpJsonTransport.JSON.writeValueAsString(arguments))))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static Map<String,Object> done() { return Map.of("role", "assistant", "content", "已修改副本，请人工运行测试"); }
    static Ticket ticket() {
        Ticket t = new Ticket(); t.id = "ticket:untrusted/../id"; t.title = "Add Java comment";
        t.mandate = new Mandate("p1", Strength.EXPLICIT, "add one Java comment", "local", "alice", "origin", false, Set.of());
        t.inputs.add(new Input("input-1", "origin", "给 Hello 加注释", false)); return t;
    }
    static Assignment assignment(Ticket ticket, String id) { return new Assignment(id, ticket.id, 0); }
    CodingExecutor executor(boolean writes, int steps) throws Exception { return new CodingExecutor(base, "", "test-model", template, workspaces, writes, steps); }
    @Test void realToolLoopReadsWritesIsolatedCopyAndDoesNotCopySecrets() throws Exception {
        model(round -> switch (round) {
            case 0 -> tool("list_files", Map.of());
            case 1 -> tool("read_file", Map.of("path", "Hello.java"));
            case 2 -> tool("write_file", Map.of("path", "Hello.java", "content", "// greeting\nclass Hello {}\n"));
            default -> done();
        });
        Ticket ticket = ticket(); var executor = executor(true, 6);
        var report = executor.execute(ticket, assignment(ticket, "a1"));
        assertEquals(ReportKind.READY, report.kind());
        assertEquals("// greeting\nclass Hello {}\n", Files.readString(executor.workspace(ticket.id).resolve("Hello.java")));
        assertEquals("class Hello {}\n", Files.readString(template.resolve("Hello.java")));
        assertFalse(Files.exists(executor.workspace(ticket.id).resolve(".env")));
        assertTrue(requests.get(2).path("messages").toString().contains("class Hello"));
        assertEquals(4, requests.size());
        assertEquals(4, requests.getFirst().path("tools").size());
    }
    @Test void traversalIsRefusedAndNoOutsideFileAppears() throws Exception {
        model(round -> round == 0 ? tool("write_file", Map.of("path", "../escape.java", "content", "bad")) : done());
        Ticket ticket = ticket(); var executor = executor(true, 3); executor.execute(ticket, assignment(ticket, "a1"));
        assertFalse(Files.exists(workspaces.resolve("escape.java")));
        assertTrue(requests.getLast().path("messages").toString().contains("REFUSED"));
    }
    @Test void writesNeedExplicitOptIn() throws Exception {
        model(round -> round == 0 ? tool("write_file", Map.of("path", "Hello.java", "content", "bad")) : done());
        Ticket ticket = ticket(); var executor = executor(false, 3); executor.execute(ticket, assignment(ticket, "a1"));
        assertEquals("class Hello {}\n", Files.readString(executor.workspace(ticket.id).resolve("Hello.java")));
        assertTrue(requests.getLast().path("messages").toString().contains("mutations disabled"));
    }
    @Test void symlinkInWorkspaceCannotBeFollowedOrOverwritten() throws Exception {
        model(round -> round == 0 ? done() : round == 1 ? tool("write_file", Map.of("path", "linked.java", "content", "bad")) : done());
        Ticket ticket = ticket(); var executor = executor(true, 3); executor.execute(ticket, assignment(ticket, "a1"));
        Path outside = temp.resolve("outside.java"); Files.writeString(outside, "keep");
        Files.createSymbolicLink(executor.workspace(ticket.id).resolve("linked.java"), outside);
        executor.execute(ticket, assignment(ticket, "a2"));
        assertEquals("keep", Files.readString(outside));
        assertTrue(requests.getLast().path("messages").toString().contains("Symlink refused"));
    }
    @Test void maxStepsEndsWithBlockedNotFakeSuccess() throws Exception {
        model(round -> tool("list_files", Map.of())); Ticket ticket = ticket();
        var report = executor(true, 2).execute(ticket, assignment(ticket, "a1"));
        assertEquals(ReportKind.BLOCKED, report.kind()); assertEquals(2, requests.size());
    }
    @Test void structuredWaitingResumesDurableTranscriptWithOnlyNewInputs() throws Exception {
        model(round -> round == 0 ? tool("human_action", Map.of("question", "注释用中文还是英文？")) : done());
        Ticket ticket = ticket(); var first = executor(true, 3);
        var waiting = first.execute(ticket, assignment(ticket, "a1"));
        assertEquals(ReportKind.WAITING, waiting.kind()); assertEquals("注释用中文还是英文？", waiting.question());
        ticket.inputs.add(new Input("input-2", "reply", "请用中文", false));
        var resumed = executor(true, 3).execute(ticket, assignment(ticket, "a2"));
        assertEquals(ReportKind.READY, resumed.kind());
        var messages = requests.getLast().path("messages");
        assertTrue(messages.toString().contains("请用中文"));
        long originalCount = java.util.stream.StreamSupport.stream(messages.spliterator(), false)
                .filter(m -> m.path("role").asText().equals("user") && m.path("content").asText().contains("给 Hello 加注释")).count();
        assertEquals(1, originalCount);
    }
    @Test void templateSymlinkAndOverlappingRootsAreRefused() throws Exception {
        assertThrows(java.io.IOException.class, () -> new CodingExecutor(base, "", "test", template, template.resolve("workers"), true, 2));
        Files.createSymbolicLink(template.resolve("escape.java"), temp.resolve("outside.java"));
        Ticket ticket = ticket(); var executor = executor(true, 2);
        assertThrows(java.io.IOException.class, () -> executor.execute(ticket, assignment(ticket, "a1")));
        assertEquals(0, requests.size());
    }
    @Test void stopDuringModelCallPreventsReturnedFileWrite() throws Exception {
        Ticket ticket = ticket(); var executor = executor(true, 3); Assignment assignment = assignment(ticket, "stop-me");
        model(round -> {
            executor.stop(ticket.id, assignment.id);
            return tool("write_file", Map.of("path", "Hello.java", "content", "bad"));
        });
        assertEquals(ReportKind.DROPPED, executor.execute(ticket, assignment).kind());
        assertEquals("class Hello {}\n", Files.readString(executor.workspace(ticket.id).resolve("Hello.java")));
    }
    @Test void oversizedToolWriteIsRefused() throws Exception {
        model(round -> round == 0 ? tool("write_file", Map.of("path", "Hello.java", "content", "x".repeat(65537))) : done());
        Ticket ticket = ticket(); var executor = executor(true, 3); executor.execute(ticket, assignment(ticket, "a1"));
        assertEquals("class Hello {}\n", Files.readString(executor.workspace(ticket.id).resolve("Hello.java")));
        assertTrue(requests.getLast().path("messages").toString().contains("exceeds 64 KiB"));
    }

    @Test void toolCannotCreateNonSourceExecutableFiles() throws Exception {
        model(round -> round == 0 ? tool("write_file", Map.of("path", "run.sh", "content", "danger")) : done());
        Ticket ticket = ticket(); var executor = executor(true, 3); executor.execute(ticket, assignment(ticket, "a1"));
        assertFalse(Files.exists(executor.workspace(ticket.id).resolve("run.sh")));
        assertTrue(requests.getLast().path("messages").toString().contains("allowlisted"));
    }

    @Test void engineStopDuringRuntimeModelCallPreventsFileMutationBeforeStopEffectRuns() throws Exception {
        try (var store = new io.github.dsh.core.SqliteStore(temp.resolve("runtime.db"))) {
            var engine = new io.github.dsh.core.Engine(store);
            engine.setPolicy(new Policy("p1", AcceptMode.OBSERVE,
                    Map.of("local", new SourcePolicy(Set.of("alice"), Set.of(), Map.of())), Set.of()));
            engine.receive(new Trigger("request", "local", List.of(new ThreadRef("local", "thread")), "alice", true,
                    true, true, "issue", java.time.Instant.now().toString(), "Add Java comment", null));
            String ticketId = engine.decide(engine.evaluateDue().getFirst().id(), Outcome.accept("comment", "add comment", Set.of())).ticketId();
            var executor = executor(true, 3);
            model(round -> {
                // 模拟另一接入线程：领域 stop 已提交，但串行 runtime 尚未处理 STOP effect。
                engine.stopTicket(ticketId);
                return tool("write_file", Map.of("path", "Hello.java", "content", "must-not-write"));
            });
            var runtime = new io.github.dsh.runtime.OrchestratorRuntime(engine, store, executor,
                    message -> "unused", java.time.Clock.systemUTC());
            runtime.drain(20);
            assertEquals("class Hello {}\n", Files.readString(executor.workspace(ticketId).resolve("Hello.java")));
            assertTrue(engine.ticket(ticketId).stopped);
            assertEquals(TicketStatus.ENDED, engine.ticket(ticketId).status);
            assertEquals(1, requests.size());
        }
    }

}
