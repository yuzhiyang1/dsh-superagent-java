package io.github.dsh.runtime;

import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import io.github.dsh.management.ResourceService;
import io.github.dsh.router.*;
import io.github.dsh.sources.*;
import java.util.*;

/** 工具身份来自宿主绑定而非模型参数；每次调用重新检查当前 Assignment。 */
public final class WorkerDomainTools implements Executor.Context {
    private final Engine engine;private final ResourceService resources;private final SourceRegistry sources;private final SourceContext sourceContext;
    public WorkerDomainTools(Engine engine,ResourceService resources,SourceRegistry sources,SourceContext sourceContext){this.engine=engine;this.resources=resources;this.sources=sources;this.sourceContext=sourceContext;}
    @Override public Ticket currentTicket(String id){return engine.ticket(id);}
    @Override public Object invoke(String tool,String ticketId,String assignmentId,Map<String,Object> args)throws Exception{
        Ticket ticket=engine.ticket(ticketId);if(ticket==null||ticket.stopped||ticket.status!=TicketStatus.OPEN||ticket.latestAssignment()==null||!ticket.latestAssignment().id.equals(assignmentId)||ticket.latestAssignment().status==AssignmentStatus.ENDED)throw new SecurityException("Assignment no longer owns this ticket");
        return switch(tool){
            case "orchestrator_read"->resources.read(string(args,"resource"),number(args,"offset",0),number(args,"limit",ResourceService.defaultLimit(string(args,"resource"))),ticketId);
            case "orchestrator_ack"->{String trigger=string(args,"trigger_id"),text=string(args,"text");AssessmentValidator.words(text,100);engine.acknowledge(ticketId,trigger);yield engine.reply(new ReplyRequest(ReplyPurpose.ACKNOWLEDGEMENT,ticketId,trigger,null,text));}
            case "orchestrator_report"->{Report report=report(args);engine.report(ticketId,assignmentId,report);yield Map.of("reported",true);}
            case "orchestrator_thread_unrelated"->{engine.markUnrelated(ticketId,string(args,"thread"),string(args,"reason"));yield Map.of("removed",true);}
            case "orchestrator_thread_follow"->Map.of("following",engine.followForContext(ticketId,MainTools.thread(string(args,"thread"))));
            case "orchestrator_trigger_handback"->Map.of("queued",engine.handback(ticketId,string(args,"trigger_id"),string(args,"reason")));
            case "orchestrator_execution_identity"->{engine.setExecutionIdentity(ticketId,string(args,"sessionId"),string(args,"directory"),Objects.toString(args.get("branch"),""));yield Map.of("registered",true);}
            case "orchestrator_claim_pull"->{
                SourceInstance source=sources.get(ticket.mandate.source());if(!(source instanceof GitHubSource github))source=sources.instances().stream().filter(s->s instanceof GitHubSource).findFirst().orElse(null);
                if(!(source instanceof GitHubSource github))throw new IllegalStateException("No GitHub source configured");
                github.claimPull(sourceContext,ticketId,number(args,"number",0));yield Map.of("bound",true);
            }
            default->throw new SecurityException("Worker may not call "+tool);
        };
    }
    public static Report report(Map<String,Object> args){
        ReportKind kind=ReportKind.valueOf(string(args,"state").toUpperCase());String summary=string(args,"summary");
        Object question=args.get("human_action");String text=null,askedBy=null;
        if(question instanceof String value)text=value;
        else if(question instanceof Map<?,?> map){text=Objects.toString(map.get("question"),null);askedBy=Objects.toString(map.get("trigger_id"),null);}
        return new Report(kind,summary,text,args.containsKey("human_action"),args.get("question_for_trigger_id")==null?askedBy:args.get("question_for_trigger_id").toString(),args.get("reply_to_trigger_id")==null?null:args.get("reply_to_trigger_id").toString());
    }
    private static String string(Map<String,Object> args,String name){Object value=args.get(name);if(!(value instanceof String text)||text.isBlank())throw new IllegalArgumentException("Missing "+name);return text;}
    private static int number(Map<String,Object> args,String name,int fallback){Object value=args.get(name);return value instanceof Number number?number.intValue():fallback;}
}
