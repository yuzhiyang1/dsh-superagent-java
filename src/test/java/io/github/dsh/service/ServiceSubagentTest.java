package io.github.dsh.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.dsh.core.Model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.dsh.config.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** 组合根使用真正 PromptService，为子 agent 组装 worker 模板，再经真实模型 HTTP 与工具执行。 */
class ServiceSubagentTest {
    @TempDir Path root;
    @Test void actualServiceChildLoadsCurrentPromptAndWritesWorkspace()throws Exception{
        List<JsonNode> requests=new CopyOnWriteArrayList<>();AtomicInteger parentSteps=new AtomicInteger(),childSteps=new AtomicInteger();
        HttpServer model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);model.setExecutor(Executors.newCachedThreadPool());
        model.createContext("/chat/completions",exchange->{
            JsonNode request=MAPPER.readTree(exchange.getRequestBody());requests.add(request);String system=request.path("messages").path(0).path("content").asText();JsonNode reply;
            if(request.path("tools").toString().contains("orchestrator_route"))reply=object("role","assistant","content","Recorded.");
            else if(system.contains("You are the subagent"))reply=childSteps.getAndIncrement()==0?ServiceIntegrationTest.call("child-write","write_file",object("path","child-result.txt","content","Written by real service subagent")):object("role","assistant","content","Child work completed");
            else reply=parentSteps.getAndIncrement()==0?ServiceIntegrationTest.call("spawn","subagent",object("task","Write child-result.txt containing a useful result")):object("role","assistant","content","Waiting for child result");
            byte[] body=MAPPER.writeValueAsBytes(object("choices",List.of(object("message",reply))));exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });model.start();
        ServiceOptions options=new ServiceOptions(root,0,URI.create("http://127.0.0.1:"+model.getAddress().getPort()+"/"),"TEST_KEY","fixture",false,Map.of("TEST_KEY","fixture"));
        try(OrchestratorService service=new OrchestratorService(options)){
            ObjectNode source=(ObjectNode)service.settings.value("source:github");source.put("enabled",false);service.settings.save("source:github",source,service.settings.revision("source:github"),"panel");
            ObjectNode executor=(ObjectNode)service.settings.value("executor");executor.put("allowWrites",true);executor.put("allowSubagents",true);service.settings.save("executor",executor,service.settings.revision("executor"),"panel");
            service.engine.setPolicy(new Policy("child-fixture",AcceptMode.OBSERVE,Map.of("fixture",new SourcePolicy(Set.of("alice"),Set.of(),Map.of())),Set.of()));
            service.engine.receive(new Trigger("child-origin","fixture",List.of(new ThreadRef("fixture","child-thread")),"alice",true,true,true,"default",Instant.now().toString(),"Delegate the file edit",null));
            String id=service.engine.decide(service.engine.evaluateDue().getFirst().id(),Outcome.accept("Delegate file edit","Write child-result.txt",Set.of())).ticketId();service.start();
            ServiceIntegrationTest.await(()->{Ticket t=service.engine.ticket(id);return t.directory!=null&&Files.exists(Path.of(t.directory).resolve("child-result.txt"));},service);
            assertEquals("Written by real service subagent",Files.readString(Path.of(service.engine.ticket(id).directory).resolve("child-result.txt")));
            ServiceIntegrationTest.await(()->childSteps.get()>=2,service);
            JsonNode childRequest=requests.stream().filter(r->r.path("messages").path(0).path("content").asText().contains("You are the subagent")).findFirst().orElseThrow();
            String instructions=childRequest.path("messages").path(0).path("content").asText();assertTrue(instructions.contains("Ticket scope"));assertTrue(instructions.contains("DSH_SUPERAGENT_TICKET"));assertFalse(childRequest.path("tools").toString().contains("orchestrator_report"));
            assertFalse(service.health().toString().contains("Unknown role"));
        }finally{model.stop(0);}
    }
}
