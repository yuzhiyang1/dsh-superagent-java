package io.github.dsh.router;

import com.fasterxml.jackson.databind.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import io.github.dsh.config.*;
import io.github.dsh.management.*;
import java.util.*;
import static io.github.dsh.config.Json.*;

/** 主会话的14个领域工具。模型只选择动作；权限、字段、版本和逐项失败由 Java 宿主执行。 */
public final class MainTools {
    private final Engine engine;private final SqliteStore store;private final VersionedSettings settings;
    private final PromptService prompts;private final ResourceService resources;private final ManagementHost host;private final CandidateContext candidates;
    public MainTools(Engine engine,SqliteStore store,VersionedSettings settings,PromptService prompts,ResourceService resources,ManagementHost host){this.engine=engine;this.store=store;this.settings=settings;this.prompts=prompts;this.resources=resources;this.host=host;candidates=new CandidateContext(engine,store);}
    public Map<String,Object> candidateCard(Evaluation evaluation){
        Map<String,Object> card=new LinkedHashMap<>(candidates.card(evaluation));Trigger trigger=engine.trigger(evaluation.primaryTriggerId());
        try{
            long deadline=System.nanoTime()+settings.value("deployment").path("admissionContextTimeoutMs").asLong(30000)*1_000_000L;
            int max=settings.value("deployment").path("admissionContextMaxPages").asInt(10);List<JsonNode> messages=new ArrayList<>();String cursor=null;
            for(int page=0;page<max&&System.nanoTime()<deadline;page++){
                JsonNode context=host.readSource(trigger.source()+"/context/"+trigger.id()+"/thread"+(cursor==null?"":"?cursor="+java.net.URLEncoder.encode(cursor,java.nio.charset.StandardCharsets.UTF_8)));
                context.path("messages").forEach(messages::add);String next=context.path("next").asText();if(next.isBlank()||next.equals(cursor))break;cursor=next;
            }
            String sourceId=Objects.toString(trigger.metadata().get("messageId"),trigger.id());int index=0;for(int i=0;i<messages.size();i++)if(messages.get(i).path("id").asText().equals(sourceId))index=i;
            List<Object> window=new ArrayList<>(),stubs=new ArrayList<>();for(int i=0;i<messages.size();i++){JsonNode item=messages.get(i);boolean visible=i<5||Math.abs(i-index)<=5;int cap=i==index?4000:Math.abs(i-index)<=2?1200:visible?400:50;String text=item.path("text").asText(item.path("body").asText());Object row=object("id",item.path("id"),"from",item.path("from"),visible?"text":"preview",text.substring(0,Math.min(cap,text.length())),"chars",text.length());(visible?window:stubs).add(row);}
            card.put("source_window",window);card.put("source_stubs",stubs);
        }catch(RuntimeException failure){card.put("context_error",failure.getClass().getSimpleName());}
        return card;
    }
    public Object invoke(String name,JsonNode args)throws Exception{
        Object result=switch(name){
            case "orchestrator_read"->resources.read(required(args,"resource"),args.path("offset").asInt(),args.path("limit").asInt(ResourceService.defaultLimit(required(args,"resource"))),null);
            case "orchestrator_route"->route(args.path("decisions"));
            case "orchestrator_ticket_retry"->engine.retryTicket(required(args,"ticket_id"));
            case "orchestrator_ticket_request_status"->Map.of("requested",engine.requestStatus(required(args,"ticket_id")));
            case "orchestrator_ticket_note"->{String text=required(args,"text");AssessmentValidator.words(text,100);yield engine.note(required(args,"ticket_id"),text);}
            case "orchestrator_trigger_requeue"->Map.of("queued",engine.reevaluate(required(args,"trigger_id")));
            case "orchestrator_eval"->{Evaluation evaluation=candidates.evaluation(required(args,"trigger_id"));if(evaluation.strength()!=Strength.INFERRED)throw new IllegalArgumentException("Explicit candidates use orchestrator_route");yield candidateCard(evaluation);}
            case "orchestrator_eval_read"->evalRead(args);
            case "orchestrator_eval_decide"->assess(args);
            case "orchestrator_reply"->{String text=required(args,"text");AssessmentValidator.words(text,100);yield engine.reply(new ReplyRequest(ReplyPurpose.valueOf(required(args,"purpose").toUpperCase()),required(args,"ticket_id"),optional(args,"trigger_id"),optional(args,"question"),text));}
            case "orchestrator_thread_unsubscribe"->Map.of("removed",engine.unsubscribe(thread(required(args,"thread"))));
            case "orchestrator_settings_save"->{String block=required(args,"block");if(block.equals("source"))block="source:"+required(args,"source");yield settings.save(block,args.path("value"),args.path("revision").asInt(-1),"tool");}
            case "orchestrator_policy_sync"->host.synchronizePolicy(required(args,"source"));
            case "orchestrator_prompts_save"->args.path("preview").asBoolean()?prompts.preview(args.path("config")):prompts.save(args.path("config"),args.path("baseRevision").asInt(-1),"tool");
            default->throw new IllegalArgumentException("Unknown main tool: "+name);
        };
        host.requestPass();return result;
    }
    private Object route(JsonNode decisions){
        if(!decisions.isArray()||decisions.isEmpty()||decisions.size()>20)throw new IllegalArgumentException("Route 1..20 decisions");
        List<Object> results=new ArrayList<>();
        for(JsonNode item:decisions){
            Map<String,Object> row=new LinkedHashMap<>();row.put("trigger_id",item.path("trigger_id").asText());row.put("kind",item.path("kind").asText());
            try{
                String triggerId=required(item,"trigger_id"),kind=required(item,"kind");
                switch(kind){
                    case "accept"->{
                        Decision accepted=engine.decisions().stream().filter(d->d.kind()==DecisionKind.ACCEPT&&d.triggerIds().contains(triggerId)).findFirst().orElse(null);
                        if(accepted!=null){row.put("decision",accepted);row.put("ticket",engine.ticket(accepted.ticketId()));break;}
                        Evaluation evaluation=candidates.evaluation(triggerId);if(evaluation.strength()!=Strength.EXPLICIT)throw new IllegalArgumentException("Inferred candidates require orchestrator_eval_decide");
                        String title=AssessmentValidator.required(item,"title",200),shortName=AssessmentValidator.worktree(required(item,"worktree_name")),ack=required(item,"acknowledgement");AssessmentValidator.words(ack,100);
                        String note=optional(item,"note");if(note!=null)AssessmentValidator.words(note,30);
                        Decision decision=decide(evaluation,Outcome.accept(title,title,Set.of()),shortName,note,ack,item);
                        row.put("decision",decision);if(decision.ticketId()!=null)row.put("ticket",engine.ticket(decision.ticketId()));
                    }
                    case "attach"->{String ticketId=required(item,"ticket_id");Ticket ticket=engine.ticket(ticketId);Trigger trigger=engine.trigger(triggerId);if(ticket==null||trigger==null||!explicitReference(ticket,trigger))throw new IllegalArgumentException("Attachment requires an explicit ticket/issue/PR/source reference");String note=optional(item,"note");if(note!=null)AssessmentValidator.words(note,30);row.put("ticket",engine.attach(ticketId,triggerId,note));}
                    case "ignore"->{Evaluation evaluation=candidates.evaluation(triggerId);row.put("decision",engine.decide(evaluation.id(),Outcome.ignore(AssessmentValidator.required(item,"reason",500))));}
                    case "stop"->{String ticketId=required(item,"ticket_id");row.put("decision",engine.decideTrigger(triggerId,new Outcome(DecisionKind.STOP,null,null,Set.of(),item.path("reason").asText("humanStop"),ticketId)));row.put("ticket",engine.ticket(ticketId));}
                    default->throw new IllegalArgumentException("Unknown routing kind");
                }
            }catch(RuntimeException failure){row.put("refused",Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()));}
            results.add(row);
        }
        return Map.of("results",results);
    }
    private Decision assess(JsonNode args){
        String triggerId=required(args,"trigger_id");Evaluation evaluation=candidates.evaluation(triggerId);
        if(evaluation.strength()!=Strength.INFERRED)throw new IllegalArgumentException("Explicit candidates use route");
        JsonNode assessment=args.has("assessment")?args.path("assessment"):args;
        Outcome outcome=AssessmentValidator.validate(assessment,engine.policy().enabledRules());
        if(outcome.kind()==DecisionKind.ACCEPT&&engine.policy().acceptMode()!=AcceptMode.AUTO){
            com.fasterxml.jackson.databind.node.ObjectNode observed=assessment.deepCopy();observed.put("would_accept",true);observed.set("proposal",object("title",outcome.title(),"scope",outcome.scope(),"worktree_name",assessment.path("worktree_name")));
            return decide(evaluation,Outcome.ignore("observed"),null,null,null,observed);
        }
        return decide(evaluation,outcome,optional(assessment,"worktree_name"),null,optional(assessment,"acknowledgement"),assessment);
    }
    private Decision decide(Evaluation evaluation,Outcome outcome,String name,String note,String acknowledgement,JsonNode audit){
        if(acknowledgement!=null)AssessmentValidator.words(acknowledgement,100);
        @SuppressWarnings("unchecked") Map<String,Object> evidence=MAPPER.convertValue(audit,Map.class);
        Outcome enriched=new Outcome(outcome.kind(),outcome.title(),outcome.scope(),outcome.matchedRules(),outcome.reason(),outcome.ticketId(),name,acknowledgement,note,evidence);
        return engine.decide(evaluation.id(),enriched);
    }

    private Object evalRead(JsonNode args)throws Exception{
        String triggerId=required(args,"trigger_id"),kind=required(args,"resource");Evaluation evaluation=candidates.evaluation(triggerId);
        if(evaluation.strength()!=Strength.INFERRED)throw new IllegalArgumentException("Explicit candidate is not an assessment");
        if(kind.equals("message"))return candidates.readMessage(triggerId,optional(args,"message_id"),args.path("offset").asInt());
        Trigger trigger=engine.trigger(triggerId);String resource=trigger.source()+"/context/"+triggerId+"/"+kind;
        if(args.has("path"))resource+="?path="+java.net.URLEncoder.encode(args.path("path").asText(),java.nio.charset.StandardCharsets.UTF_8);
        if(args.has("cursor"))resource+="?cursor="+java.net.URLEncoder.encode(args.path("cursor").asText(),java.nio.charset.StandardCharsets.UTF_8);
        if(args.has("image_id"))resource+="?image_id="+java.net.URLEncoder.encode(args.path("image_id").asText(),java.nio.charset.StandardCharsets.UTF_8);
        return host.readSource(resource);
    }
    private boolean explicitReference(Ticket ticket,Trigger trigger){
        String text=trigger.text();if(text.contains(ticket.id)||text.contains(ticket.originTriggerId))return true;
        for(Channel channel:ticket.channels){String number=channel.address().get("issue"),repository=channel.address().get("repository"),message=channel.address().get("messageId");
            if(number!=null&&(text.matches("(?s).*#"+java.util.regex.Pattern.quote(number)+"(?![0-9]).*")||repository!=null&&text.contains(repository+"/pull/"+number)||repository!=null&&text.contains(repository+"/issues/"+number)))return true;
            if(message!=null&&text.contains(message))return true;
        }return false;
    }
    public static ThreadRef thread(String key){int i=key.indexOf(':');if(i<1||i==key.length()-1)throw new IllegalArgumentException("Expected source:thread");return new ThreadRef(key.substring(0,i),key.substring(i+1));}
    private static String required(JsonNode args,String name){return AssessmentValidator.required(args,name,16000);}
    private static String optional(JsonNode args,String name){return args.path(name).isTextual()&&!args.path(name).asText().isBlank()?args.path(name).asText():null;}
    public static List<Map<String,Object>> schemas(){
        Map<String,String> descriptions=new LinkedHashMap<>();
        descriptions.put("orchestrator_read","Read paginated resource. Fields resource, offset=0, limit=20.");
        descriptions.put("orchestrator_route","Route 1..20 decisions in order. Each: kind accept|attach|ignore|stop, trigger_id; accept needs title, worktree_name (1-4 English kebab words), acknowledgement <=100 words; attach/stop ticket_id; ignore reason. Optional note <=30 words. Only explicit accept here.");
        descriptions.put("orchestrator_ticket_retry","Retry failed delivery/blocked/failed ticket. ticket_id.");descriptions.put("orchestrator_ticket_request_status","Wake waiting ticket for status. ticket_id.");descriptions.put("orchestrator_ticket_note","Send context, not authority. ticket_id,text<=100 words.");descriptions.put("orchestrator_trigger_requeue","Requeue trigger_id, refuses owned human followup.");
        descriptions.put("orchestrator_eval","Read inferred candidate facts. trigger_id.");descriptions.put("orchestrator_eval_read","Expand inferred candidate. trigger_id, resource message|root|parent|thread|file|directory|image, optional message_id/path/cursor/image_id/offset.");
        descriptions.put("orchestrator_eval_decide","trigger_id, assessment {decision accept|ignore|unavailable, title,scope,reason,worktree_name,acknowledgement,intent_evidence,rule_assessments:[{rule_id,matched,reason}] exactly every enabled rule, direction:{implementation_requested,target_basis:specified|existing-standard|undetermined,requires_design_decision,evidence}}.");
        descriptions.put("orchestrator_reply","ticket_id,purpose acknowledgement|answer|result|attention,text<=100 words; trigger_id for reply to human; question for attention.");descriptions.put("orchestrator_thread_unsubscribe","Unsubscribe all tickets from thread source:thread.");descriptions.put("orchestrator_settings_save","block policy|eval|executor|pause|source; source if needed; value complete block, revision. Cannot change execution/account/deployment settings.");descriptions.put("orchestrator_policy_sync","Sync configured source membership. source.");descriptions.put("orchestrator_prompts_save","config,baseRevision; preview=true composes without saving.");
        return descriptions.entrySet().stream().map(e->Map.<String,Object>of("type","function","function",Map.of("name",e.getKey(),"description",e.getValue(),"parameters",Map.of("type","object","additionalProperties",true)))).toList();
    }
}
