package io.github.dsh.execution;

import io.github.dsh.adapters.HttpJsonTransport;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 收件箱与模型历史一起原子保存：重启后不会把已经入历史的 Input 再投一次。 */
public final class SessionStore {
    private final Path root;
    public SessionStore(Path root)throws IOException{this.root=root;WorkspaceFiles.rejectSymlinks(root);Files.createDirectories(root);}
    public static final class Session {
        public String id,model,role,preset,ticketId,assignmentId,parentId,workspace,lastText="";
        public String childTask="",childStatus="",childResult="";
        public int depth; public volatile boolean busy,interrupted,handedOff;
        public volatile long backgroundSince;
        public List<Map<String,Object>> messages=new ArrayList<>();
        public LinkedHashMap<String,String> inbox=new LinkedHashMap<>();
        public Set<String> historyIds=new LinkedHashSet<>(),completedCalls=new LinkedHashSet<>(),domainInputIds=new LinkedHashSet<>();
        public Set<Long> promptRevisions=new LinkedHashSet<>();
        public Map<String,String> toolResults=new LinkedHashMap<>();
        public List<Map<String,Object>> todos=new ArrayList<>();
        public Session(){}
    }
    public synchronized Session load(String id)throws IOException{
        Path p=path(id);WorkspaceFiles.rejectSymlinks(p);
        if(!Files.exists(p))return null;
        if(Files.size(p)>64L*1024*1024)throw new IOException("Session exceeds 64 MiB");
        return HttpJsonTransport.JSON.readValue(Files.readAllBytes(p),Session.class);
    }
    public synchronized void save(Session session)throws IOException{WorkspaceFiles.atomicWrite(path(session.id),HttpJsonTransport.JSON.writeValueAsBytes(session));}
    public synchronized List<Session> all()throws IOException{
        List<Session> result=new ArrayList<>();
        try(var files=Files.list(root)){for(Path p:files.filter(p->p.getFileName().toString().endsWith(".json")).toList()){
            WorkspaceFiles.rejectSymlinks(p);result.add(HttpJsonTransport.JSON.readValue(Files.readAllBytes(p),Session.class));}}
        return result;
    }
    public synchronized boolean deliver(Session session,String id,String text)throws IOException{
        if(session.historyIds.contains(id)||session.inbox.containsKey(id))return false;
        session.inbox.put(id,text);save(session);return true;
    }
    /** 先把消息持久化到历史，再通知领域层已读边界。 */
    public synchronized List<String> take(Session session)throws IOException{
        List<String> ids=new ArrayList<>(session.inbox.keySet());
        session.inbox.forEach((id,text)->{session.messages.add(message("user",text));session.historyIds.add(id);});
        session.inbox.clear();save(session);return ids;
    }
    public Path path(String id){return root.resolve(UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8))+".json");}
    public static Map<String,Object> message(String role,String text){return new LinkedHashMap<>(Map.of("role",role,"content",text));}
}
