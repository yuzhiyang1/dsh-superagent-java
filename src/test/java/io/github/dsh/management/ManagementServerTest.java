package io.github.dsh.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsh.config.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static io.github.dsh.config.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagementServerTest {
    @TempDir Path directory;
    SqliteStore store;Engine engine;VersionedSettings settings;PromptService prompts;ManagementServer server;
    HttpClient client=HttpClient.newHttpClient();
    ManagementHost host=new ManagementHost(){
        public JsonNode setup(){return object("problem",null,"feishuProblem",null,"accounts",object("repository","acme/widgets","agent","fixture-agent"));}
        public JsonNode health(){return object("runtime",object("state","ready","detail","fixture"));}
        public List<Map<String,String>> environment(String ticket){return List.of(Map.of("key","DSH_SUPERAGENT_TICKET","value",ticket==null?"":ticket));}
        public JsonNode synchronizePolicy(String source){return object("allowed",List.of(object("id","bob")),"bots",List.of(),"admission",object());}
        public void checkSetup(){}
    };
    @BeforeEach void start()throws Exception{store=new SqliteStore(directory.resolve("state.db"));engine=new Engine(store);settings=new VersionedSettings(store,engine,directory,host);prompts=new PromptService(store,settings,host);settings.save("policy",object("rules",List.of(),"sources",object("github",object("allowed",List.of(object("id","Alice")),"bots",List.of(),"admission",object()))),0,"panel");server=new ManagementServer(engine,store,settings,prompts,host,0);server.start();}
    @AfterEach void close(){server.close();store.close();}
    HttpResponse<String> call(String path,String method,JsonNode body)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/orchestrator/api/"+path)).header("x-orchestrator-request","1").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode get(String path)throws Exception{var r=call(path,"GET",null);assertEquals(200,r.statusCode(),r.body());return parse(r.body());}
    Trigger trigger(String id,String thread,String actor){return new Trigger(id,"github",List.of(new ThreadRef("github",thread)),actor,true,true,true,"issues",Instant.now().toString(),"Fix a typo in "+id,new Channel("github",thread,Map.of("repository","acme/widgets","number",thread)));}
    String accept(String triggerId,String thread){engine.receive(trigger(triggerId,thread,"alice"));Evaluation e=engine.evaluateDue().stream().filter(v->v.triggerIds().contains(triggerId)).findFirst().orElseThrow();return engine.decide(e.id(),Outcome.accept("修复标题 "+thread,"Fix title",Set.of())).ticketId();}
    @Test void enforcesOriginCsrfHostAndBoundedBodies()throws Exception {
        var uri=URI.create("http://127.0.0.1:"+server.port()+"/orchestrator/api/state");
        assertEquals(403,client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403,client.send(HttpRequest.newBuilder(uri).header("x-orchestrator-request","1").header("Origin","https://evil.example").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        var large=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/orchestrator/api/executor/pause")).header("x-orchestrator-request","1").POST(HttpRequest.BodyPublishers.ofString("x".repeat(128*1024+1))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(400,large.statusCode());
        assertEquals(400,call("executor/pause","POST",node("not an object")).statusCode());
        assertEquals(404,call("unknown","GET",null).statusCode());
        var staticPage=client.send(HttpRequest.newBuilder(URI.create(server.url())).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,staticPage.statusCode());assertTrue(staticPage.body().contains("app.js"));assertTrue(staticPage.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
    }
    @Test void savesIndependentBlocksConflictsHistoriesAndRestores()throws Exception{
        JsonNode initial=get("settings");assertTrue(initial.path("sources").isArray());int rev=settings.revision("eval");
        var save=call("settings/eval","PUT",object("value",object("mode","auto"),"revision",rev));assertEquals(200,save.statusCode(),save.body());int next=parse(save.body()).path("revision").asInt();assertEquals(120,settings.value("eval").path("timeoutSeconds").asInt());
        assertEquals(409,call("settings/eval","PUT",object("value",object("mode","off"),"revision",rev)).statusCode());
        assertEquals(200,call("executor/pause","POST",object("paused",true)).statusCode());assertTrue(engine.processing().paused());assertEquals(next,settings.revision("eval"));
        assertEquals(400,call("settings/executor","PUT",object("value",object("paused",true),"revision",0)).statusCode());
        JsonNode history=get("settings/history?block=eval");assertEquals(1,history.path("items").size());
        assertEquals(200,call("settings/eval","PUT",object("value",object("mode","off"),"revision",next)).statusCode());
        assertEquals(200,call("settings/eval/restore","POST",object("revision",history.path("items").get(0).path("revision"),"baseRevision",settings.revision("eval"))).statusCode());assertEquals("auto",settings.value("eval").path("mode").asText());
    }
    @Test void modelToolCannotEscalateEnvironmentOrAccounts(){
        ObjectNode executor=(ObjectNode)settings.value("executor").deepCopy();executor.put("allowShell",true);assertThrows(ApiException.class,()->settings.save("executor",executor,0,"tool"));
        ObjectNode source=(ObjectNode)settings.value("source:github").deepCopy();((ObjectNode)source.path("config")).put("tokenEnvironment","PERSONAL_TOKEN");assertThrows(ApiException.class,()->settings.save("source:github",source,0,"tool"));
        ObjectNode safe=(ObjectNode)settings.value("executor").deepCopy();safe.put("maxWorkers",4);settings.save("executor",safe,0,"tool");assertEquals(4,settings.value("executor").path("maxWorkers").asInt());
    }
    @Test void sourceDisableDoesNotCorruptPolicyVersionAndGitHubIdentitiesIgnoreCase(){String id=accept("t1","12");assertNotNull(id);ObjectNode source=(ObjectNode)settings.value("source:github").deepCopy();source.put("enabled",false);assertDoesNotThrow(()->settings.save("source:github",source,0,"panel"));assertEquals(settings.policyVersion(),engine.policy().version());}
    @Test void promptVersionsPreviewTemplatesSkillsAndOptimisticConflicts()throws Exception{
        JsonNode view=get("prompts");assertEquals(15,view.path("parts").size());assertTrue(prompts.compose("orchestrator",List.of()).contains("## Policy"));
        ObjectNode c=prompts.defaults();c.put("communication","Use clear words.");((ObjectNode)c.path("skills")).put("identities","Alice is a maintainer.");
        assertEquals(200,call("prompts","PUT",object("config",c,"baseRevision",0)).statusCode());assertEquals(409,call("prompts","PUT",object("config",c,"baseRevision",0)).statusCode());
        assertEquals(2,get("prompts").path("revisions").size());assertEquals(0,get("prompts?revision=0").path("current").path("revision").asInt());
        JsonNode preview=parse(call("prompts/preview","POST",c).body());assertTrue(preview.path("orchestrator").asText().contains("Use clear words."));assertEquals("Alice is a maintainer.",preview.path("skills").path("identities").asText());
        ObjectNode bad=c.deepCopy();bad.set("overrides",object("runtime/environment",object("text","Missing variable")));assertEquals(400,call("prompts/preview","POST",bad).statusCode());
        bad=c.deepCopy();((ObjectNode)bad.path("skills")).put("github","reserved");assertEquals(400,call("prompts/preview","POST",bad).statusCode());
    }
    @Test void promptDefaultDriftAndIdenticalOverrideNormalization(){ObjectNode c=prompts.defaults();JsonNode p=prompts.view(null,0).path("parts").get(0);c.set("overrides",object(p.path("key").asText(),object("text",p.path("text").asText(),"base",p.path("base").asText())));assertFalse(prompts.validate(c).has("overrides"));((ObjectNode)c.path("overrides").path(p.path("key").asText())).put("text","Changed text").put("base","previous-version");assertEquals(1,prompts.defaultChanged(prompts.validate(c)).size());}
    @Test void allReadPagesUseDomainRecordsAndCorrectLoad()throws Exception{
        String first=accept("t1","12"),second=accept("t2","13");engine.assignmentStarted(first,engine.ticket(first).latestAssignment().id);
        assertEquals(1,get("state").path("load").path("running").asInt());assertEquals(1,get("state").path("load").path("queued").asInt());
        for(String path:List.of("state","overview","sources","settings","threads?filter=all","threads/github%3A12","tickets","tickets/"+first,"decisions","models","policy/history","prompts","deployment","resources?resource=ticket/"+first))assertNotNull(get(path));
        assertEquals(1,get("search?q=%2312").path("threads").size());assertEquals(1,get("threads?filter=all&limit=1").path("items").size());assertNotNull(get("threads?filter=all&limit=1").get("next_offset"));
    }
    @Test void ticketRetryAnswerStopAndOwnedFollowupRequeueMutateRealEngine()throws Exception{
        String id=accept("t1","12");engine.assignmentStarted(id,engine.ticket(id).latestAssignment().id);engine.report(id,engine.ticket(id).latestAssignment().id,new Report(ReportKind.BLOCKED,"Need clarification","Which wording?"));
        assertEquals(200,call("tickets/"+id+"/answer","POST",object("text","Use the existing label.")).statusCode());assertTrue(engine.ticket(id).inputs.size()>1);
        engine.receive(trigger("follow","12","Alice"));assertThrows(IllegalStateException.class,()->engine.reevaluate("follow"));assertEquals(200,call("triggers/follow/requeue","POST",null).statusCode());assertTrue(engine.triggerDisposition("follow").requeued);
        assertEquals(200,call("tickets/"+id+"/stop","POST",null).statusCode());assertTrue(engine.ticket(id).stopped);assertEquals(400,call("tickets/"+id+"/retry","POST",null).statusCode());assertEquals(404,call("tickets/missing/stop","POST",null).statusCode());
    }
    @Test void workerResourcesRejectOtherTicketAndSharedThreadMetadataLeaks(){
        String a=accept("t1","12"),b=accept("t2","13");engine.followThread(b,new ThreadRef("github","12"),true);ResourceService resources=server.resources();
        assertThrows(ApiException.class,()->resources.read("settings",0,4000,a));assertThrows(ApiException.class,()->resources.read("ticket/"+b,0,4000,a));assertThrows(ApiException.class,()->resources.read("trigger/t2",0,4000,a));
        String own=resources.read("ticket/"+a,0,4000,a).toString();assertFalse(own.contains(b),own);String shared=resources.read("thread/github:12",0,4000,b).toString();assertFalse(shared.contains(a),shared);
        assertThrows(ApiException.class,()->resources.read("prompts/current",0,4000,a));prompts.recordUse("worker:"+a,0);assertNotNull(resources.read("prompts/0",0,4000,a));
        assertThrows(ApiException.class,()->resources.read("github/issues/12",0,4000,a));assertThrows(ApiException.class,()->resources.read("trigger/t1",0,8001,a));
    }
    @Test void sourceSyncAndSubscriptionMutationsUseVersionedStore()throws Exception{int revision=settings.revision("policy");assertEquals(200,call("policy-sync/github","POST",null).statusCode());assertTrue(settings.revision("policy")>revision);assertEquals("bob",settings.value("policy").path("sources").path("github").path("allowed").get(0).path("id").asText());}
}
