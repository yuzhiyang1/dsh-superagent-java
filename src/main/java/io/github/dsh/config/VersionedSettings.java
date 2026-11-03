package io.github.dsh.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import io.github.dsh.management.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static io.github.dsh.config.Json.*;

/**
 * 按块乐观锁与不可变历史。所有版本与 Ticket 同库存储，可一起备份。
 * panel 允许改机器环境；tool 不允许改账户、命令或部署边界。
 */
public final class VersionedSettings {
    private final SqliteStore store;
    private final Engine engine;
    private final ManagementHost host;
    private final Path stateDir;
    public static final List<String> BLOCKS=List.of("policy","eval","executor","source:github","source:feishu","pause","deployment");
    public VersionedSettings(SqliteStore store,Engine engine,Path stateDir,ManagementHost host) {
        this.store=store; this.engine=engine; this.stateDir=stateDir.toAbsolutePath(); this.host=host;
        ConfigurationMigrations.settings(store);
    }
    public Path stateDir() { return stateDir; }
    public ObjectNode defaults(String block) {
        return switch(block) {
            case "policy" -> object("rules",List.of(
                object("id","copy","name","Copy fixes","enabled",true,"instruction","Explicit requests to correct a typo, wording, punctuation or existing translation. Preserve product promises and business meaning."),
                object("id","visual","name","Visual fixes","enabled",true,"instruction","Explicit implementation requests for local spacing, alignment, overflow or styling, with a specified target or an objectively established existing standard/correct state to restore. Locating an element or calling it inconsistent, unattractive or hard to understand does not establish a target. Do not invent visual style, information hierarchy, rendering or interaction decisions. No redesign, new interaction or behavior. A known spacing defect does not authorize adjacent undefined restyling; assess only a clearly separable authorized scope. If direction or scope is unsettled, observe only: no acknowledgement, worker or unsolicited question.")),"sources",object());
            case "eval" -> object("mode","observe","timeoutSeconds",120,"model",null);
            case "executor" -> object("model",null,"maxWorkers",3,"directory","","prepare","","blockedCommands",List.of(),"allowShell",false,"allowWrites",false,"allowNetwork",false,"allowSubagents",false,"allowPublish",false,"gitRepository","");
            case "source:github" -> object("enabled",true,"config",object("repository","","cli","","observeIssues",false,"observePulls",false,"observeComments",false,"observeCi",false,"pollSeconds",60,"failuresBeforeError",3,"pageSize",50,"botLogin","","apiBase","https://api.github.com","tokenEnvironment","GITHUB_TOKEN"));
            case "source:feishu" -> object("enabled",false,"config",object("cli","","chats",List.of(),"directMessages",true,"otherChats","none","readPageSize",20,"linkTimeoutMs",5000,"botId","","appId","","apiBase","https://open.feishu.cn/open-apis/","tokenEnvironment","FEISHU_TENANT_ACCESS_TOKEN"));
            case "pause" -> object("paused",false);
            case "deployment" -> object("promptMaxChars",100_000,"admissionContextMaxPages",10,"admissionContextTimeoutMs",15_000);
            default -> throw new ApiException(404,"Unknown settings block: "+block);
        };
    }
    public ObjectNode block(String name) {
        ObjectNode saved=store.getState("settings:block",name,ObjectNode.class);
        return saved==null?object("block",name,"revision",0,"value",defaults(name)):saved.deepCopy();
    }
    public JsonNode value(String name) { return block(name).path("value"); }
    public int revision(String name) { return block(name).path("revision").asInt(); }
    public int currentRevision() { Integer n=store.getState("settings","revision",Integer.class); return n==null?0:n; }
    public ObjectNode current() {
        ObjectNode sources=object(); for(String id:List.of("github","feishu")) sources.set(id,value("source:"+id));
        return object("paused",value("pause").path("paused"),"policy",value("policy"),"eval",value("eval"),"executor",value("executor"),"sources",sources);
    }
    public boolean paused() { return value("pause").path("paused").asBoolean(); }
    public int promptMaxChars() { return value("deployment").path("promptMaxChars").asInt(); }
    public ObjectNode machine() { ObjectNode result=(ObjectNode)value("deployment").deepCopy(); result.put("stateDir",stateDir.toString()); return result; }
    public String policyVersion() { return hash(object("rules",value("policy").path("rules"),"sources",value("policy").path("sources"),"mode",value("eval").path("mode")).toString()); }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public ObjectNode save(String name,JsonNode input,int baseRevision,String origin) {
        if(!BLOCKS.contains(name)) throw new ApiException(404,"Unknown settings block: "+name);
        JsonNode parsed=validate(name,input);
        JsonNode before=value(name);
        if(!"panel".equals(origin)) {
            if(name.equals("deployment")) throw new ApiException(400,"Only the panel may change deployment limits");
            if(name.equals("executor") && (!sameEnvironment(before,parsed)||!before.path("directory").equals(parsed.path("directory"))||!before.path("prepare").equals(parsed.path("prepare"))||!before.path("blockedCommands").equals(parsed.path("blockedCommands")))) throw new ApiException(400,"Only the panel may change execution environment");
            if(name.startsWith("source:") && (!before.path("config").path("cli").equals(parsed.path("config").path("cli"))||!before.path("config").path("repository").equals(parsed.path("config").path("repository"))||accountChanged(before.path("config"),parsed.path("config")))) throw new ApiException(400,"Only the panel may change source accounts");
        }
        if(revision(name)!=baseRevision) throw new ApiException(409,"Settings changed. Reload before saving.");
        host.validateSettings(name,parsed);
        ObjectNode result=store.atomic(()->{
            if(revision(name)!=baseRevision) throw new ApiException(409,"Settings changed during checks. Reload before saving.");
            if(value(name).equals(parsed)) return object("saved",true,"revision",baseRevision,"policy_changed",false,"changed",false);
            int next=currentRevision()+1; String policyBefore=policyVersion();
            ObjectNode old=current();Map<String,Integer> oldInputs=new LinkedHashMap<>();engine.tickets().forEach(ticket->oldInputs.put(ticket.id,ticket.inputs.size()));
            store.putState("settings:block",name,object("block",name,"revision",baseRevision+1,"value",parsed));
            store.putState("settings","revision",next);
            applyToEngine();
            String policyAfter=policyVersion();
            List<String> changed=new ArrayList<>(); diff("",before,parsed,changed);
            List<String> notified=engine.tickets().stream().filter(ticket->ticket.inputs.size()>oldInputs.getOrDefault(ticket.id,ticket.inputs.size())).map(ticket->ticket.id).toList();
            if(!"tool".equals(origin))store.coalesceEffect(EffectKind.NOTICE,"settings:"+name,Map.of("text",object("settings_saved",name,"changed",changed.stream().limit(20).toList(),"read","settings/"+name).toString(),"revision",Integer.toString(next)),System.currentTimeMillis());
            ObjectNode record=object("revision",next,"previousRevision",next-1,"savedAt",Instant.now().toString(),"source",origin,"block",name,"policyVersion",policyAfter,"policyChanged",!policyAfter.equals(policyBefore),"changes",changed,"before",before,"value",parsed,"settings",current(),"notified",notified);
            store.putState("settings:history",Integer.toString(next),record);
            return object("saved",true,"revision",baseRevision+1,"policy_changed",!policyAfter.equals(policyBefore),"changed",true);
        });
        if(result.path("changed").asBoolean()) { result.put("restarting",host.settingsChanged(name,parsed)); } else result.put("restarting",false);
        result.remove("changed"); result.put("context_delivery",deliveryState("settings:"+name)); host.requestPass(); return result;
    }
    public String deliveryState(String key) { return store.effects().stream().anyMatch(effect->effect.kind()==EffectKind.NOTICE&&effect.key().equals(key))?"pending":"injected"; }
    public ObjectNode pause(boolean paused,String origin) { save("pause",object("paused",paused),revision("pause"),origin); return object("paused",paused); }
    public void applyToEngine() {
        JsonNode policy=value("policy"); Map<String,SourcePolicy> sources=new LinkedHashMap<>();
        policy.path("sources").fields().forEachRemaining(entry->{
            String id=entry.getKey(); JsonNode part=entry.getValue();
            Set<String> allowed=new LinkedHashSet<>(),bots=new LinkedHashSet<>(); part.path("allowed").forEach(a->allowed.add(a.path("id").asText())); part.path("bots").forEach(a->bots.add(a.path("id").asText()));
            Map<String,AdmissionRow> rows=new LinkedHashMap<>();
            part.path("admission").fields().forEachRemaining(row->rows.put(row.getKey(),new AdmissionRow(admission(row.getValue(),"listedUnaddressed","inferred"),admission(row.getValue(),"unlistedAddressed","ignore"),admission(row.getValue(),"unlistedUnaddressed","ignore"))));
            sources.put(id,new SourcePolicy(allowed,bots,rows,id.equals("github")));
        });
        Set<String> rules=new LinkedHashSet<>(); policy.path("rules").forEach(rule->{if(rule.path("enabled").asBoolean())rules.add(rule.path("id").asText());});
        Map<String,String> instructions=new LinkedHashMap<>();policy.path("rules").forEach(rule->{if(rule.path("enabled").asBoolean())instructions.put(rule.path("id").asText(),rule.path("instruction").asText());});
        engine.setPolicy(new Policy(policyVersion(),AcceptMode.valueOf(value("eval").path("mode").asText().toUpperCase(Locale.ROOT)),sources,rules,instructions));
        engine.setProcessing(paused(),value("eval").path("timeoutSeconds").asLong()*1000);
    }
    private Admission admission(JsonNode row,String key,String fallback) { return Admission.valueOf(row.path(key).asText(fallback).toUpperCase(Locale.ROOT)); }
    public ObjectNode history(int offset,int limit,String block) {
        List<JsonNode> versions=new ArrayList<>(); for(int n=currentRevision();n>=1;n--) { JsonNode v=store.getState("settings:history",Integer.toString(n),JsonNode.class); if(v!=null&&(block==null||block.equals(v.path("block").asText()))) versions.add(v); }
        int start=Math.min(Math.max(0,offset),versions.size()),end=Math.min(start+Math.max(1,Math.min(limit,100)),versions.size());
        return object("current",currentRevision(),"policyVersion",policyVersion(),"items",versions.subList(start,end),"next_offset",end<versions.size()?end:null,"total",versions.size());
    }
    public JsonNode version(int revision) { JsonNode v=store.getState("settings:history",Integer.toString(revision),JsonNode.class); if(v==null) throw new ApiException(404,"Unknown settings revision"); return v; }
    public ObjectNode restore(String name,int revision,int base,String origin) { JsonNode old=version(revision); if(!name.equals(old.path("block").asText())) throw new ApiException(400,"Revision belongs to another block"); return save(name,old.path("value"),base,origin); }
    public static void diff(String path,JsonNode a,JsonNode b,List<String> result) {
        if(Objects.equals(a,b)) return;
        if(a!=null&&b!=null&&a.isObject()&&b.isObject()) { Set<String> keys=new TreeSet<>();a.fieldNames().forEachRemaining(keys::add);b.fieldNames().forEachRemaining(keys::add);for(String key:keys)diff(path.isEmpty()?key:path+"."+key,a.get(key),b.get(key),result); }
        else result.add(path);
    }
    public ObjectNode validate(String name,JsonNode input) {
        ObjectNode d=defaults(name); List<String> keys=new ArrayList<>();d.fieldNames().forEachRemaining(keys::add); Json.keys(input,name,keys.toArray(String[]::new)); ObjectNode v=Json.defaults(input,d);
        switch(name) {
            case "pause" -> bool(v.get("paused"),"paused");
            case "deployment" -> {integer(v.get("promptMaxChars"),"promptMaxChars",1000,1_000_000);integer(v.get("admissionContextMaxPages"),"admissionContextMaxPages",1,100);integer(v.get("admissionContextTimeoutMs"),"admissionContextTimeoutMs",1,90_000);}
            case "eval" -> {choice(v.get("mode"),"mode","off","observe","auto");integer(v.get("timeoutSeconds"),"timeoutSeconds",30,600);model(v.get("model"));}
            case "executor" -> { model(v.get("model"));for(String flag:List.of("allowShell","allowWrites","allowNetwork","allowSubagents","allowPublish"))bool(v.get(flag),flag);string(v.get("gitRepository"),"gitRepository",0,4096);integer(v.get("maxWorkers"),"maxWorkers",1,16);String path=string(v.get("directory"),"directory",0,4096);if(!path.isEmpty()&&!Path.of(path).isAbsolute()) invalid("directory","Absolute path required");string(v.get("prepare"),"prepare",0,4000);requireArray(v.get("blockedCommands"),"blockedCommands");v.path("blockedCommands").forEach(c->{String s=string(c,"blockedCommands",1,200);if(!s.matches("[A-Za-z0-9._-]+")||Set.of(".","..","gh","lark-cli","git").contains(s))invalid("blockedCommands","Invalid or reserved command");}); }
            case "policy" -> validatePolicy(v);
            default -> {bool(v.get("enabled"),"enabled");ObjectNode config=(ObjectNode)d.path("config");List<String> fields=new ArrayList<>();config.fieldNames().forEachRemaining(fields::add);Json.keys(v.get("config"),"config",fields.toArray(String[]::new));ObjectNode c=Json.defaults(v.path("config"),config);v.set("config",c);string(c.get("cli"),"config.cli",0,4096);String endpoint=string(c.get("apiBase"),"config.apiBase",1,4096);try {java.net.URI u=java.net.URI.create(endpoint);if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)invalid("apiBase","HTTP(S) endpoint without embedded credentials required");} catch(IllegalArgumentException e){invalid("apiBase","Invalid URI");}String token=string(c.get("tokenEnvironment"),"config.tokenEnvironment",1,200);if(!token.matches("[A-Za-z_][A-Za-z0-9_]*"))invalid("tokenEnvironment","Environment variable name required, never a token");
                if(name.equals("source:github")) {string(c.get("botLogin"),"config.botLogin",0,200);String repo=string(c.get("repository"),"repository",0,300);if(!repo.isEmpty()&&!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))invalid("repository","Expected owner/name");for(String key:List.of("observeIssues","observePulls","observeComments","observeCi"))bool(c.get(key),key);integer(c.get("pollSeconds"),"pollSeconds",60,3600);integer(c.get("failuresBeforeError"),"failuresBeforeError",1,20);integer(c.get("pageSize"),"pageSize",1,100);}
                else {integer(c.get("readPageSize"),"readPageSize",1,50);integer(c.get("linkTimeoutMs"),"linkTimeoutMs",1,90000);string(c.get("botId"),"config.botId",0,200);string(c.get("appId"),"config.appId",0,200);bool(c.get("directMessages"),"directMessages");choice(c.get("otherChats"),"otherChats","none","mentions");requireArray(c.get("chats"),"chats");Set<String> ids=new HashSet<>();c.path("chats").forEach(row->{Json.keys(row,"chats","chatId","name","listen");String id=string(row.get("chatId"),"chatId",1,200);if(!id.matches("oc_[A-Za-z0-9]+"))invalid("chatId","Expected Feishu chat ID");if(!ids.add(id))invalid("chats","Duplicate chat");if(row.has("name"))string(row.get("name"),"name",0,60);choice(row.get("listen"),"listen","all","mentions");});}
            }
        }
        return v;
    }
    private void validatePolicy(ObjectNode v) {
        requireArray(v.get("rules"),"rules");Set<String> ids=new HashSet<>();v.path("rules").forEach(rule->{Json.keys(rule,"rules","id","name","enabled","instruction");String id=string(rule.get("id"),"rules.id",1,80);if(!id.matches("[A-Za-z0-9_-]+")||!ids.add(id))invalid("rules.id","Invalid or duplicate id");String name=string(rule.get("name"),"rules.name",1,100).strip(),instruction=string(rule.get("instruction"),"rules.instruction",1,2000).strip();if(name.isEmpty()||instruction.isEmpty())invalid("rules","Name and instruction must not be blank");((ObjectNode)rule).put("name",name).put("instruction",instruction);bool(rule.get("enabled"),"rules.enabled");});
        requireObject(v.get("sources"),"sources").fields().forEachRemaining(entry->{if(!Set.of("github","feishu").contains(entry.getKey())) invalid("sources."+entry.getKey(),"Unknown Source");JsonNode part=entry.getValue();Json.keys(part,"sources."+entry.getKey(),"allowed","bots","syncChat","admission");for(String group:List.of("allowed","bots")){if(!part.has(group))continue;requireArray(part.get(group),group);Set<String> actors=new HashSet<>();part.path(group).forEach(a->{Json.keys(a,group,"id","name");String id=string(a.get("id"),group+".id",1,200);if(entry.getKey().equals("github")){if(!id.matches(group.equals("bots")?"[A-Za-z0-9-]+(\\[bot\\])?":"[A-Za-z0-9-]+"))invalid(group+".id","Invalid GitHub identity");}else if(!id.matches(group.equals("bots")?"(cli|ou)_[A-Za-z0-9]+":"ou_[A-Za-z0-9]+"))invalid(group+".id","Invalid Feishu identity");if(!actors.add(entry.getKey().equals("github")?id.toLowerCase(Locale.ROOT):id))invalid(group,"Duplicate identity");if(a.has("name"))string(a.get("name"),group+".name",0,100);});}if(part.has("syncChat")){String sync=string(part.get("syncChat"),"syncChat",0,200);if(!entry.getKey().equals("feishu")||!sync.matches("(oc_[A-Za-z0-9]+)?"))invalid("syncChat","Expected Feishu chat ID");}
            if(part.has("admission")) requireObject(part.get("admission"),"admission").fields().forEachRemaining(row->{Json.keys(row.getValue(),"admission","listedUnaddressed","unlistedAddressed","unlistedUnaddressed");row.getValue().fields().forEachRemaining(cell->{if(cell.getKey().equals("unlistedAddressed"))choice(cell.getValue(),cell.getKey(),"explicit","inferred","ignore");else choice(cell.getValue(),cell.getKey(),"inferred","ignore");});});
        });
    }
    private static boolean accountChanged(JsonNode a,JsonNode b) {for(String key:List.of("botLogin","botId","appId","apiBase","tokenEnvironment"))if(!a.path(key).equals(b.path(key)))return true;return false;}
    private static boolean sameEnvironment(JsonNode a,JsonNode b) { for(String key:List.of("allowShell","allowWrites","allowNetwork","allowSubagents","allowPublish","gitRepository"))if(!a.path(key).equals(b.path(key)))return false;return true; }
    private static void model(JsonNode value) { if(value.isNull())return;Json.keys(value,"model","provider","model","reasoningEffort");string(value.get("provider"),"model.provider",1,200);string(value.get("model"),"model.model",1,200);if(value.has("reasoningEffort"))string(value.get("reasoningEffort"),"model.reasoningEffort",1,100); }
    private static void requireArray(JsonNode n,String path) {if(n==null||!n.isArray())invalid(path,"Expected array");}
}
