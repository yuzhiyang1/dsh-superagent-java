package io.github.dsh.runtime;

import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import java.time.Clock;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 审计事件到主会话通知在同一事务推进游标，进程重启不会漏掉已提交报告。 */
public final class DomainNoticeRelay {
    private final Engine engine;private final SqliteStore store;private final Clock clock;private final ObjectMapper json=new ObjectMapper();
    public DomainNoticeRelay(Engine engine,SqliteStore store,Clock clock){this.engine=engine;this.store=store;this.clock=clock;}
    public void advance(){store.atomic(()->{
        Long saved=store.getState("runtime","eventCursor",Long.class);long cursor=saved==null?0:saved;
        for(DomainEvent event:store.events(cursor,1000)){
            try{
                String type=event.type();
                if(type.equals("eval.started")){
                    String eval=event.payload().get("evalId");if(eval!=null)store.enqueue(EffectKind.ASSESSMENT,eval,Map.of("triggerId",event.payload().getOrDefault("triggerId","")),clock.millis());
                }else{
                    Map<String,Object> data=new LinkedHashMap<>();data.put("kind",type);data.putAll(event.payload());
                    String ticketId=event.payload().get("ticketId");Ticket ticket=ticketId==null?null:engine.ticket(ticketId);
                    if(ticket!=null){data.put("ticket",Map.of("id",ticket.id,"title",ticket.title,"status",ticket.status));if(ticket.latestAssignment()!=null&&ticket.latestAssignment().report!=null)data.put("report",ticket.latestAssignment().report);if(ticket.attention!=null)data.put("attention",ticket.attention);}
                    String triggerId=event.payload().get("triggerId");if(triggerId!=null){Trigger trigger=engine.trigger(triggerId);if(trigger!=null)data.put("trigger",trigger);}
                    EffectKind kind=type.contains("report")||type.contains("attention")||type.equals("trigger.followup")?EffectKind.RELAY:EffectKind.NOTICE;
                    store.enqueue(kind,"event-"+event.sequence(),Map.of("text",json.writeValueAsString(data)),clock.millis());
                }
            }catch(Exception error){throw new IllegalStateException(error);}
            cursor=event.sequence();
        }
        store.putState("runtime","eventCursor",cursor);return null;
    });}
}
