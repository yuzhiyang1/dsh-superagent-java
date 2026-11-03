package io.github.dsh.sources;

import io.github.dsh.core.Model.OutboxMessage;
import java.util.*;

/** 按来源实例路由，不将不存在/停用的来源当成发送成功。 */
public final class SourceRegistry implements AutoCloseable {
    private final Map<String, SourceInstance> sources = new LinkedHashMap<>();
    public synchronized void register(SourceInstance source) {
        if (sources.putIfAbsent(source.id(), source) != null) throw new IllegalArgumentException("Duplicate source: " + source.id());
    }
    public synchronized void remove(String id)throws Exception {SourceInstance previous=sources.remove(id);if(previous!=null)previous.close();}
    public synchronized void upsert(SourceInstance source) throws Exception {
        SourceInstance previous=sources.put(source.id(),source);
        if(previous!=null&&previous!=source)previous.close();
    }
    public Object readContext(SourceContext context,io.github.dsh.core.Model.Trigger trigger,String part,String cursor)throws Exception {
        SourceInstance source=get(trigger.source());
        if(source==null||!source.enabled())throw new IllegalArgumentException("Source unavailable");
        return source.readContext(context,trigger,part,cursor);
    }
    public SourceInstance.Find find(String query){
        Set<String> threads=new LinkedHashSet<>(),terms=new LinkedHashSet<>();for(var source:instances()){var found=source.find(query);threads.addAll(found.threads());terms.addAll(found.terms());}return new SourceInstance.Find(List.copyOf(threads),List.copyOf(terms));
    }
    public synchronized SourceInstance get(String id) { return sources.get(id); }
    public synchronized List<SourceInstance> instances() { return List.copyOf(sources.values()); }
    public SourceInstance.Delivery send(SourceContext context, OutboxMessage message) throws Exception {
        SourceInstance source = get(message.channel().source());
        return source == null || !source.enabled() ? new SourceInstance.Delivery("waiting", null) : source.send(context, message);
    }
    public Object read(SourceContext context, String sourceId, String resource) throws Exception {
        SourceInstance source = get(sourceId);
        if (source == null || !source.enabled()) throw new IllegalArgumentException("Source unavailable");
        return source.read(context, resource);
    }
    @Override public void close() throws Exception {
        Exception failure = null;
        for (SourceInstance source : instances()) try { source.close(); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }
}
