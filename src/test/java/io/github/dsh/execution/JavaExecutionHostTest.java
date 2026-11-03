package io.github.dsh.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.dsh.adapters.HttpJsonTransport;
import io.github.dsh.core.Model.*;
import io.github.dsh.runtime.Executor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** 不 mock 文件/进程/git：仅用 loopback HTTP 代替付费模型。 */
class JavaExecutionHostTest {
    @TempDir Path temp;HttpServer server;URI base;
    List<JsonNode> requests=new CopyOnWriteArrayList<>();List<JavaExecutionHost> hosts=new ArrayList<>();
    @BeforeEach void setup()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(Executors.newCachedThreadPool());server.start();base=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");}
    @AfterEach void cleanup()throws Exception{for(var h:hosts)h.close();server.stop(0);}
    ExecutionSettings.Builder config(){return ExecutionSettings.builder(temp.resolve("state"),temp.resolve("work")).foregroundWait(Duration.ofMillis(25)).modelRetryBackoff(Duration.ofMillis(10)).shutdownTimeout(Duration.ofMillis(500));}
    JavaExecutionHost host(ExecutionSettings config)throws Exception{var h=new JavaExecutionHost(base,"","fixture",config);hosts.add(h);return h;}
    void model(Function<JsonNode,Map<String,Object>> handler){server.createContext("/chat/completions",e->{JsonNode request=HttpJsonTransport.JSON.readTree(e.getRequestBody());requests.add(request);byte[] bytes=HttpJsonTransport.JSON.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",handler.apply(request)))));e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});}
    static Map<String,Object> text(String value){return Map.of("role","assistant","content",value);}
    static Map<String,Object> call(String name,Map<String,Object> args){try{return Map.of("role","assistant","tool_calls",List.of(Map.of("id",UUID.randomUUID().toString(),"type","function","function",Map.of("name",name,"arguments",HttpJsonTransport.JSON.writeValueAsString(args)))));}catch(Exception e){throw new IllegalStateException(e);}}
    static Ticket ticket(){Ticket t=new Ticket();t.id="test-ticket";t.shortName="fix-widget-123456";t.branch="agent/"+t.shortName;t.title="Fix widget";t.mandate=new Mandate("p1",Strength.EXPLICIT,"fix widget","local","alice","origin",false,Set.of());t.inputs.add(new Input("input-1","origin","Fix widget",false));return t;}
    static Assignment assignment(Ticket t,String id){Assignment a=new Assignment(id,t.id,System.currentTimeMillis());t.assignments.add(a);return a;}
    @FunctionalInterface interface Check { boolean getAsBoolean() throws Exception; }
    static void await(Check value)throws Exception{long end=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(!value.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);assertTrue(value.getAsBoolean());}
    @Test void realFileToolsDurableModelAndInputHistory()throws Exception{
        AtomicInteger turn=new AtomicInteger();model(r->switch(turn.getAndIncrement()){case 0->call("write_file",Map.of("path","run.sh","content","#!/bin/sh\nprintf hello\n"));case 1->call("grep",Map.of("pattern","printf"));default->text("Waiting for more input");});
        var settings=config().allowWrites(true).build();Ticket t=ticket();Assignment a=assignment(t,"a1");var first=host(settings);assertEquals(ReportKind.WAITING,first.execute(t,a).kind());
        assertTrue(Files.readString(first.workspace(t.id).resolve("run.sh")).contains("printf"));assertEquals(Set.of("input-1"),first.consumedInputIds(t.id));first.close();hosts.remove(first);
        t.inputs.add(new Input("input-2","reply","Continue",false));var second=new JavaExecutionHost(base,"","different-model",settings);hosts.add(second);second.execute(t,assignment(t,"a2"));
        assertEquals("fixture",requests.getLast().path("model").asText());assertEquals(1,requests.getLast().path("messages").findValuesAsText("content").stream().filter(s->s.contains("input_id=input-1,")).count());
        assertEquals(Set.of("input-1","input-2"),second.consumedInputIds(t.id));assertTrue(second.sessionView(t.id).get("workspace").toString().endsWith(t.shortName));
    }
    @Test void realShellBackgroundKeepsSlotAndCompletionWakes()throws Exception{
        AtomicInteger turn=new AtomicInteger();model(r->turn.getAndIncrement()==0?call("bash",Map.of("command","sleep 0.2; printf completed","run_in_background",true)):text("Waiting on the command"));
        Ticket t=ticket();var a=assignment(t,"a1");var h=host(config().allowShell(true).build());assertEquals(ReportKind.PROGRESS,h.execute(t,a).kind());assertTrue(h.backgroundState(t.id).holdsSlot());
        await(()->h.pollEvents().stream().anyMatch(e->e.kind()==Executor.EventKind.WAKE));
        assertTrue(h.sessionView(t.id).toString().contains("completed"));assertEquals(ReportKind.WAITING,h.execute(t,a).kind());assertFalse(h.backgroundState(t.id).holdsSlot());
    }
    @Test void handoffLeavesServerRunningWithoutSlotOrCompletionWake()throws Exception{
        AtomicInteger turn=new AtomicInteger();model(r->turn.getAndIncrement()==0?call("bash",Map.of("command","sleep 0.3; printf review-server-finished","run_in_background",true)):call("orchestrator_report",Map.of("state","ready","summary","Server ready for review")));
        Ticket t=ticket();var a=assignment(t,"a1");var h=host(config().allowShell(true).build());assertEquals(ReportKind.READY,h.execute(t,a).kind());
        assertFalse(h.backgroundState(t.id).holdsSlot());assertEquals(1,h.backgroundState(t.id).runningJobs());Thread.sleep(500);assertFalse(h.pollEvents().stream().anyMatch(e->e.kind()==Executor.EventKind.WAKE));
        assertTrue(h.sessionView(t.id).get("inbox").toString().contains("review-server-finished"));
    }
    @Test void backgroundDeadlineKillsJobAndReportsWaiting()throws Exception{
        AtomicInteger turn=new AtomicInteger();model(r->turn.getAndIncrement()==0?call("bash",Map.of("command","sleep 30","run_in_background",true)):text("Command running"));
        Ticket t=ticket();var h=host(config().allowShell(true).backgroundLimit(Duration.ofMillis(100)).build());h.execute(t,assignment(t,"a1"));
        await(()->h.pollEvents().stream().anyMatch(e->e.kind()==Executor.EventKind.REPORT&&e.report().kind()==ReportKind.WAITING));assertEquals(0,h.backgroundState(t.id).runningJobs());assertFalse(h.backgroundState(t.id).holdsSlot());
    }
    @Test void stopKillsProcessesAndRevokesLateWrites()throws Exception{
        AtomicInteger round=new AtomicInteger();model(r->round.getAndIncrement()==0?call("bash",Map.of("command","sleep 20; printf unsafe > late.txt","run_in_background",true)):text("Command running"));
        Ticket t=ticket();var a=assignment(t,"a1");var h=host(config().allowShell(true).allowWrites(true).build());h.execute(t,a);h.stop(t.id,a.id);assertEquals(0,h.backgroundState(t.id).runningJobs());assertFalse(Files.exists(h.workspace(t.id).resolve("late.txt")));
        assertEquals(ReportKind.DROPPED,h.execute(t,a).kind());
    }
    @Test void subagentUsesRealToolLoopCannotInvokeDomainToolsAndWakesParent()throws Exception{
        AtomicInteger worker=new AtomicInteger(),child=new AtomicInteger();model(r->{boolean sub=r.path("messages").get(0).path("content").asText().contains("You are the subagent");if(sub)return child.getAndIncrement()==0?call("orchestrator_ack",Map.of("trigger_id","origin","text","bad")):text("Child finding");return worker.getAndIncrement()==0?call("subagent",Map.of("task","Inspect the widget")):text("Waiting for child");});
        Ticket t=ticket();var h=host(config().allowSubagents(true).build());h.execute(t,assignment(t,"a1"));await(()->h.sessionView(t.id).toString().contains("Child finding"));
        assertTrue(requests.stream().anyMatch(r->r.path("messages").toString().contains("Only ticket worker")));assertTrue(requests.stream().filter(r->r.path("messages").get(0).path("content").asText().contains("You are the subagent")).noneMatch(r->r.path("tools").toString().contains("orchestrator_ack")));
    }
    @Test void localGitWorktreePrepareRunsOnceAndKeepsBranch()throws Exception{
        Path repo=Files.createDirectory(temp.resolve("repo"));git(repo,"init");git(repo,"config","user.email","fixture@example.invalid");git(repo,"config","user.name","Fixture");Files.writeString(repo.resolve("README.md"),"before\n");git(repo,"add",".");git(repo,"commit","-m","initial");
        model(r->text("Workspace inspected"));Ticket t=ticket();var h=host(config().gitRepository(repo).allowWrites(true).allowShell(true).build());h.execute(t,assignment(t,"a1"));Path work=h.workspace(t.id);assertTrue(Files.exists(work.resolve(".git")));assertEquals(t.branch,git(work,"branch","--show-current").trim());
        Files.writeString(work.resolve("persistent.txt"),"keep");h.execute(t,assignment(t,"a2"));assertEquals("keep",Files.readString(work.resolve("persistent.txt")));assertEquals(2,git(repo,"worktree","list").lines().count());
    }
    @Test void reportsPreserveQuestionClearingAndReplyTarget()throws Exception{
        Map<String,Object> args=new LinkedHashMap<>();args.put("state","ready");args.put("summary","Verified");args.put("human_action",null);args.put("reply_to_trigger_id","reply");model(r->call("orchestrator_report",args));
        Ticket t=ticket();var h=host(config().build());Report report=h.execute(t,assignment(t,"a1"));assertTrue(report.questionSpecified());assertNull(report.question());assertNull(report.askedBy());assertEquals("reply",report.answers());
    }
    @Test void defaultCapabilitiesRefuseShellAndWritesAndNeverPretendReady()throws Exception{
        AtomicInteger round=new AtomicInteger();model(r->switch(round.getAndIncrement()){case 0->call("bash",Map.of("command","touch escaped"));case 1->call("write_file",Map.of("path","bad.txt","content","bad"));default->text("No changes performed");});
        Ticket t=ticket();var h=host(config().build());assertEquals(ReportKind.WAITING,h.execute(t,assignment(t,"a1")).kind());assertFalse(Files.exists(h.workspace(t.id).resolve("escaped")));assertFalse(Files.exists(h.workspace(t.id).resolve("bad.txt")));assertTrue(requests.getLast().path("messages").toString().contains("mutations disabled"));
    }
    @Test void routerNoWaitAndRoleRestrictionsAreEnforced()throws Exception{
        try(var tools=new RouterWorkTools(config().allowShell(true).build(),temp.resolve("router"))){
            assertThrows(java.io.IOException.class,()->tools.invoke("bash",Map.of("command","sleep 1")));assertThrows(java.io.IOException.class,()->tools.invoke("bash",Map.of("command","printf hi","timeoutMs",30001)));assertThrows(java.io.IOException.class,()->tools.invoke("write_file",Map.of("path","x","content","bad")));
            assertTrue(tools.invoke("bash",Map.of("command","printf hello")).toString().contains("hello"));assertThrows(java.io.IOException.class,()->tools.invoke("job_output",Map.of("job_id","fake","wait",true,"timeout_ms",5001)));
        }
    }
    @Test void environmentDropsSecretsAndRejectsCredentialConfig()throws Exception{
        assertThrows(IllegalArgumentException.class,()->config().environment(Map.of("GITHUB_TOKEN","secret")).build());
        var env=new WorkerEnvironment(config().build());var vars=env.process(Map.of("DSH_SUPERAGENT_NAME","fixture"));assertFalse(vars.containsKey("GITHUB_TOKEN"));assertEquals("1",vars.get("GIT_CONFIG_NOSYSTEM"));
        Path repo=Files.createDirectory(temp.resolve("creds"));git(repo,"init");git(repo,"config","credential.helper","store");assertThrows(java.io.IOException.class,()->env.verifyRepository(repo));
    }
    @Test void transientModelFailuresRetryThreeTimesWithoutDuplicatingInputs()throws Exception{
        AtomicInteger calls=new AtomicInteger();server.createContext("/chat/completions",e->{JsonNode r=HttpJsonTransport.JSON.readTree(e.getRequestBody());requests.add(r);int status=calls.getAndIncrement()<3?503:200;byte[] data=HttpJsonTransport.JSON.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",text("Recovered")))));e.sendResponseHeaders(status,data.length);e.getResponseBody().write(data);e.close();});
        Ticket t=ticket();var h=host(config().build());assertEquals(ReportKind.WAITING,h.execute(t,assignment(t,"a1")).kind());assertEquals(4,calls.get());assertEquals(Set.of("input-1"),h.consumedInputIds(t.id));
    }
    @Test void prepareFailureIsNotAutomaticallyRetriedAndEmptyDirectoryCanRetry()throws Exception{
        model(r->text("Prepared"));Ticket t=ticket();var h=host(config().allowWrites(true).allowShell(true).prepareCommand("printf x >> attempts; mkdir \"$DSH_SUPERAGENT_DIR\"; exit 7").build());
        assertThrows(JavaExecutionHost.PrepareFailed.class,()->h.execute(t,assignment(t,"a1")));assertEquals("x",Files.readString(temp.resolve("work/attempts")));assertFalse(Files.exists(h.workspace(t.id)));assertEquals(0,requests.size());
        assertThrows(JavaExecutionHost.PrepareFailed.class,()->h.execute(t,assignment(t,"a2")));assertEquals("xx",Files.readString(temp.resolve("work/attempts")));
    }
    @Test void progressAndWorkerDomainToolsUseContextAndLatestPrompts()throws Exception{
        AtomicInteger round=new AtomicInteger(),revision=new AtomicInteger();List<String> actions=new ArrayList<>();
        model(r->switch(round.getAndIncrement()){case 0->call("orchestrator_ack",Map.of("trigger_id","origin","text","I will fix the widget"));case 1->call("orchestrator_thread_follow",Map.of("thread","github:2","reason","Related design"));case 2->call("orchestrator_trigger_handback",Map.of("trigger_id","other","reason","Different scope"));case 3->call("orchestrator_report",Map.of("state","progress","summary","Inspecting"));default->text("Waiting");});
        Ticket t=ticket();var h=host(config().prompts((ticket,role,env)->new ExecutionSettings.Prompt("Prompt revision "+revision.incrementAndGet(),revision.get(),Map.of("fixture","Skill content"))).build());
        h.setContext(new Executor.Context(){public Object invoke(String name,String ticket,String assignment,Map<String,Object> a){actions.add(name);return Map.of("accepted",true);}});h.execute(t,assignment(t,"a1"));
        assertTrue(actions.containsAll(List.of("orchestrator_execution_identity","orchestrator_ack","orchestrator_thread_follow","orchestrator_trigger_handback","orchestrator_report")));assertTrue(requests.getLast().path("messages").get(0).path("content").asText().contains("Prompt revision 5"));assertEquals(5,((Set<?>)h.sessionView(t.id).get("promptRevisions")).size());
    }
    @Test void shellOutputIsBoundedAndJobsAreSessionPrivate()throws Exception{
        try(var jobs=new ShellJobs(temp.resolve("shell"),128,Duration.ofMillis(200),j->{})){
            var j=jobs.start("one","a","yes output | head -n 10000",temp,Map.of("PATH","/usr/bin:/bin"),Duration.ofSeconds(2));jobs.waitFor("one",j.id,Duration.ofSeconds(3));assertTrue(j.truncated);assertTrue(j.output.getBytes().length<=128);assertThrows(java.io.IOException.class,()->jobs.get("two",j.id));assertThrows(java.io.IOException.class,()->jobs.kill("two",j.id,"bad"));
        }
    }
    @Test void imageToolSendsMultimodalContentRatherThanBase64Text()throws Exception{
        Path template=Files.createDirectory(temp.resolve("template"));var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);javax.imageio.ImageIO.write(image,"png",template.resolve("image.png").toFile());
        AtomicInteger turn=new AtomicInteger();model(r->turn.getAndIncrement()==0?call("read_image",Map.of("path","image.png")):text("Image inspected"));Ticket t=ticket();var h=host(config().template(template).build());h.execute(t,assignment(t,"a1"));assertTrue(requests.getLast().path("messages").toString().contains("data:image/png;base64,"));assertTrue(requests.getLast().path("messages").toString().contains("image_url"));
    }
    @Test void concurrentInputArrivingDuringModelCallIsReadNextStep()throws Exception{
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger turn=new AtomicInteger();model(r->{if(turn.getAndIncrement()==0){started.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}return text("Turn result");});
        Ticket t=ticket();var a=assignment(t,"a1");var h=host(config().build());try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var future=pool.submit(()->h.execute(t,a));assertTrue(started.await(2,TimeUnit.SECONDS));Input next=new Input("input-2","followup","Additional detail",false);assertTrue(h.deliver(t,a,List.of(next)));release.countDown();future.get(5,TimeUnit.SECONDS);assertEquals(2,requests.size());assertTrue(h.consumedInputIds(t.id).contains("input-2"));
        }
    }
    @Test void crashRecoveryRepairsPendingToolWithoutBlindReplay()throws Exception{
        model(r->text("Recovered safely"));var settings=config().allowWrites(true).build();SessionStore store=new SessionStore(settings.stateDirectory.resolve("sessions"));SessionStore.Session session=new SessionStore.Session();session.id="worker-test-ticket";session.ticketId="test-ticket";session.assignmentId="a0";session.model="fixture";session.role="worker";session.preset="dsh-superagent-worker";session.workspace=settings.workspaceRoot.resolve("fix-widget-123456").toString();Files.createDirectories(Path.of(session.workspace));session.busy=true;
        session.messages.add(new LinkedHashMap<>(call("write_file",Map.of("path","never.txt","content","bad"))));store.save(session);Ticket t=ticket();var h=host(settings);h.execute(t,assignment(t,"a1"));assertFalse(Files.exists(h.workspace(t.id).resolve("never.txt")));assertTrue(requests.getFirst().path("messages").toString().contains("outcome uncertain"));assertTrue(requests.getFirst().path("messages").toString().contains("Host restarted"));
    }
    @Test void actualOptInPullRequestAdapterIsCalledAndDefaultIsDraft()throws Exception{
        AtomicInteger created=new AtomicInteger();AtomicBoolean draft=new AtomicBoolean();AtomicInteger turn=new AtomicInteger();Ticket t=ticket();model(r->turn.getAndIncrement()==0?call("create_pull_request",Map.of("title","Fix widget","body","Tests passed","head",t.branch)):text("PR created, waiting for review"));
        var pr=new ExecutionSettings.PullRequests(){public ExecutionSettings.PullRequest create(String title,String body,String head,String base,boolean isDraft){created.incrementAndGet();draft.set(isDraft);return new ExecutionSettings.PullRequest(42,"https://example.invalid/pull/42",head,isDraft);}};
        var h=host(config().allowPublish(true).pullRequests(pr).build());h.execute(t,assignment(t,"a1"));assertEquals(1,created.get());assertTrue(draft.get());assertTrue(requests.getLast().path("messages").toString().contains("42"));
    }
    @Test void cancellationRevokesSubagentBeforeHandoffReturns()throws Exception{
        CountDownLatch childEntered=new CountDownLatch(1);AtomicInteger parent=new AtomicInteger();model(r->{if(r.path("messages").get(0).path("content").asText().contains("You are the subagent")){childEntered.countDown();try{Thread.sleep(300);}catch(InterruptedException e){Thread.currentThread().interrupt();}return call("write_file",Map.of("path","late-child.txt","content","bad"));}if(parent.getAndIncrement()==0)return call("subagent",Map.of("task","Inspect slowly"));try{childEntered.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return call("orchestrator_report",Map.of("state","ready","summary","Ready"));});
        Ticket t=ticket();var h=host(config().allowWrites(true).allowSubagents(true).build());assertEquals(ReportKind.READY,h.execute(t,assignment(t,"a1")).kind());Thread.sleep(400);assertFalse(Files.exists(h.workspace(t.id).resolve("late-child.txt")));assertEquals(0,h.backgroundState(t.id).runningSubagents());assertFalse(h.pollEvents().stream().anyMatch(e->e.kind()==Executor.EventKind.WAKE));
    }
    @Test void nestedChildrenShareWorkPermissionsAndReturnToParent()throws Exception{
        AtomicInteger worker=new AtomicInteger();model(r->{String system=r.path("messages").get(0).path("content").asText();String history=r.path("messages").toString();if(!system.contains("You are the subagent"))return worker.getAndIncrement()==0?call("subagent",Map.of("task","child-task")):text("Waiting");if(history.contains("grandchild-task"))return text("Grandchild completed");if(!history.contains("Inspect via grandchild"))return call("subagent",Map.of("task","grandchild-task"));return text("Child completed");});
        // 单独验证嵌套上限与继承；子 agent 的创建指令自身会进入历史，后续 turn 应结束。
        Ticket t=ticket();var h=host(config().allowSubagents(true).maxDepth(2).maxSteps(4).build());h.execute(t,assignment(t,"a1"));await(()->h.sessionView(t.id).toString().contains("depth=2"));assertTrue(requests.stream().filter(r->r.path("messages").get(0).path("content").asText().contains("You are the subagent")).noneMatch(r->r.path("tools").toString().contains("create_pull_request")));
    }
    @Test void hotReconfigureKeepsActiveJobAndExistingModelButChangesFutureSessions()throws Exception{
        AtomicInteger turn=new AtomicInteger();model(r->turn.getAndIncrement()==0?call("bash",Map.of("command","sleep 0.3; printf retained","run_in_background",true)):text("Waiting"));
        Ticket t=ticket();var a=assignment(t,"a1");var h=host(config().allowShell(true).build());h.execute(t,a);int events=h.pollEvents().size();h.reconfigure("new-model",config().allowShell(true).allowWrites(true).build());assertEquals(1,h.backgroundState(t.id).runningJobs());assertTrue(h.pollEvents().size()>=events);await(()->h.backgroundState(t.id).runningJobs()==0);h.execute(t,a);assertEquals("fixture",requests.getLast().path("model").asText());
        Ticket other=ticket();other.id="new-ticket";other.shortName="new-ticket-work";h.execute(other,assignment(other,"a-new"));assertEquals("new-model",requests.getLast().path("model").asText());
    }
    private static String git(Path cwd,String...args)throws Exception{List<String> cmd=new ArrayList<>(List.of("git","-C",cwd.toString()));cmd.addAll(List.of(args));Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start();String out=new String(p.getInputStream().readAllBytes());assertEquals(0,p.waitFor(),out);return out;}
}
