package io.github.dsh.management;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsh.config.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import static io.github.dsh.config.Json.*;

/** Browser QA fixture: actual Engine/SQLite/HTTP, no fabricated page responses or external calls. */
public final class PanelFixture {
    public static void main(String[] args)throws Exception{
        Path path=args.length>0?Path.of(args[0]):Files.createTempDirectory("dsh-panel-qa");Files.createDirectories(path);SqliteStore store=new SqliteStore(path.resolve("state.sqlite"));Engine engine=new Engine(store);
        ManagementHost host=new ManagementHost(){public JsonNode setup(){return object("problem",null,"feishuProblem",null,"accounts",object("repository","fixture/widgets","agent","fixture-agent"));}public JsonNode health(){return object("runtime",object("state","ready","detail","Browser fixture"),"github",object("state","ready","detail","No external calls"));}public void checkSetup(){}public JsonNode transcript(String ticket){return object("events",List.of(object("role","assistant","text","Inspecting source files in the isolated fixture")));}public List<Map<String,String>> environment(String ticket){return List.of(Map.of("key","DSH_SUPERAGENT_TICKET","value",ticket==null?"":ticket));}};
        VersionedSettings settings=new VersionedSettings(store,engine,path,host);PromptService prompts=new PromptService(store,settings,host);
        if(engine.tickets().isEmpty()){
            settings.save("policy",object("rules",List.of(),"sources",object("github",object("allowed",List.of(object("id","alice")),"bots",List.of(),"admission",object()))),0,"panel");
            for(int i=12;i<=15;i++){String thread=Integer.toString(i),trigger="qa-trigger-"+i;engine.receive(new Trigger(trigger,"github",List.of(new ThreadRef("github",thread)),"alice",true,true,true,"issues",Instant.now().toString(),"Please fix the existing page title for issue #"+i+". <script>alert('xss')</script>",new Channel("github",thread,Map.of("number",thread))));Evaluation eval=engine.evaluateDue().getFirst();String id=engine.decide(eval.id(),Outcome.accept("修复页面标题 #"+i,"Correct existing title only",Set.of())).ticketId();engine.describeThread("github:"+i,Map.of("title","页面标题修复 #"+i));if(i<14||i==15){engine.assignmentStarted(id,engine.ticket(id).latestAssignment().id);if(i==15)engine.report(id,engine.ticket(id).latestAssignment().id,new Report(ReportKind.FAILED,"Fixture execution failed; safe to retry"));if(i==12)engine.report(id,engine.ticket(id).latestAssignment().id,new Report(ReportKind.BLOCKED,"Need exact wording","Should the title use the repository's existing spelling?"));}}
            engine.receive(new Trigger("qa-filtered","github",List.of(new ThreadRef("github","20")),"outsider",true,true,true,"issues",Instant.now().toString(),"Not an allowed sender",null));
        }
        ManagementServer server=new ManagementServer(engine,store,settings,prompts,host,args.length>1?Integer.parseInt(args[1]):3083);server.start();System.out.println(server.url());Runtime.getRuntime().addShutdownHook(new Thread(()->{server.close();store.close();}));new CountDownLatch(1).await();
    }
}
