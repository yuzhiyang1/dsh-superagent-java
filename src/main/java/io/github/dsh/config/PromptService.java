package io.github.dsh.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.github.dsh.core.SqliteStore;
import io.github.dsh.core.Model.EffectKind;
import io.github.dsh.management.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import static io.github.dsh.config.Json.*;

/**
 * 原版英文提示完整随 JAR 分发，不翻译模型约束。每次模型请求读取最新版本。
 * 保存只追加版本，恢复也是一次新保存；模板替换一次完成，注入值不再被解释。
 */
public final class PromptService {
    public static final List<String> TEAM=List.of("communication","scope","delivery");
    public static final String SKILL_PREFIX="dsh-superagent-";
    private final SqliteStore store;
    private final VersionedSettings settings;
    private final ManagementHost host;
    private final LinkedHashMap<String,ObjectNode> parts=new LinkedHashMap<>();
    public PromptService(SqliteStore store,VersionedSettings settings,ManagementHost host) {
        this.store=store;this.settings=settings;this.host=host;
        ConfigurationMigrations.prompts(store,defaults());
        for(String part:List.of("shared","orchestrator","worker","subscriptions","replies","dispatch","work","admission")) {
            String prefix=Set.of("subscriptions","replies","dispatch","work").contains(part)?"## "+part+"\n":"";
            add("runtime/"+part,prefix+read("prompts/"+part+".md"),Set.of("orchestrator","dispatch","admission").contains(part)?List.of("orchestrator"):Set.of("worker","work").contains(part)?List.of("worker"):List.of("orchestrator","worker"),List.of());
        }
        for(String role:List.of("orchestrator","worker"))add("runtime/handoff/"+role,read("prompts/handoff.md")+"\n"+read("prompts/handoff-"+role+".md"),List.of(role),List.of());
        add("runtime/environment","Execution environment (from this installation's settings; it takes precedence over any repository or account named elsewhere in these instructions; an empty value means that part is not configured here). The prepare command of a ticket directory reads the same variables:\n{variables}",List.of("orchestrator","worker"),List.of("{variables}"));
        String policy="## Policy\nPolicy version {version}; accept mode {mode}. The accept mode and the rules decide proactive candidates only: an explicit request is routed as dispatch says, in any mode and whatever the rules.";
        add("runtime/policy",policy+" The enabled rules, by name and id:\n{rules}",List.of("orchestrator"),List.of("{version}","{mode}","{rules}"));
        add("runtime/policy/none",policy+" No rule is enabled: a proactive candidate can only be ignored.",List.of("orchestrator"),List.of("{version}","{mode}"));
        for(String source:List.of("feishu","github"))add("skill/"+source,read("skills/dsh-superagent-"+source+"/SKILL.md")+"\n\n## CLI usage\n"+read("prompts/cli.md"),List.of("orchestrator","worker"),List.of());
    }
    private void add(String key,String text,List<String> roles,List<String> placeholders) { parts.put(key,object("key",key,"text",text,"base",VersionedSettings.hash("prompt-built-in:"+text),"roles",roles,"placeholders",placeholders)); }
    private static String read(String resource) {
        try(InputStream input=PromptService.class.getClassLoader().getResourceAsStream(resource)){if(input==null)throw new IllegalStateException("Missing bundled prompt "+resource);return new String(input.readAllBytes(),StandardCharsets.UTF_8).strip();}catch(IOException e){throw new UncheckedIOException(e);}
    }
    public ObjectNode defaults() {ObjectNode out=object("skills",object());for(String name:TEAM)out.put(name,read("prompts/team/"+name+".md"));return out;}
    public int revision() {Integer revision=store.getState("prompts","revision",Integer.class);return revision==null?0:revision;}
    public ObjectNode current() {return version(revision());}
    public ObjectNode version(int n) {
        ObjectNode found=store.getState("prompts:version",Integer.toString(n),ObjectNode.class);
        if(found!=null)return found.deepCopy();
        if(n==0&&revision()==0)return object("revision",0,"previousRevision",null,"savedAt","","source","defaults","config",defaults());
        throw new ApiException(404,"Unknown prompt revision: "+n);
    }
    public ObjectNode validate(JsonNode input) {
        Json.keys(input,"prompts","communication","scope","delivery","skills","overrides");ObjectNode result=requireObject(input,"prompts").deepCopy();
        for(String section:TEAM)string(result.get(section),section,0,1_000_000);
        ObjectNode skills=requireObject(result.get("skills"),"skills");skills.fields().forEachRemaining(e->{if(!e.getKey().matches("[a-z][a-z0-9-]*")||Set.of("github","feishu").contains(e.getKey()))invalid("skills."+e.getKey(),"Invalid or reserved skill name");string(e.getValue(),"skills."+e.getKey(),0,1_000_000);});
        ObjectNode overrides=object();JsonNode saved=current().path("config").path("overrides");
        if(input.has("overrides")) requireObject(input.get("overrides"),"overrides").fields().forEachRemaining(e->{
            String key=e.getKey();JsonNode override=e.getValue(),part=parts.get(key);Json.keys(override,"overrides."+key,"text","base");
            if(part==null) {if(saved.has(key))return;throw new ApiException(400,"Unknown prompt part: "+key);}
            String text=string(override.get("text"),key,1,1_000_000);if(text.isBlank())invalid(key,"Prompt part must not be blank");
            part.path("placeholders").forEach(p->{if(!text.contains(p.asText()))invalid(key,"Required placeholder: "+p.asText());});
            if(override.has("base"))string(override.get("base"),key+".base",0,300);
            if(text.equals(part.path("text").asText()))return;
            String base=override.path("base").asText(saved.path(key).path("base").asText(part.path("base").asText()));
            overrides.set(key,object("text",text,"base",base));
        });
        if(overrides.isEmpty())result.remove("overrides");else result.set("overrides",overrides);
        if(result.toString().length()>settings.promptMaxChars())throw new ApiException(400,"Prompt configuration exceeds "+settings.promptMaxChars()+" characters");
        return result;
    }
    public ObjectNode save(JsonNode input,int baseRevision,String origin) {
        ObjectNode config=validate(input);
        ObjectNode saved=store.atomic(()->{
            ObjectNode previous=current();if(previous.path("revision").asInt()!=baseRevision)throw new ApiException(409,"Prompt version changed to "+revision()+"; compare the latest version before saving");
            if(baseRevision==0&&store.getState("prompts:version","0",ObjectNode.class)==null)store.putState("prompts:version","0",previous);
            config.path("overrides").fields().forEachRemaining(e->{JsonNode p=parts.get(e.getKey());String base=e.getValue().path("base").asText();if(base.equals(p.path("base").asText()))store.putState("prompts:builtin",base,p.path("text").asText());});
            int next=baseRevision+1;ObjectNode v=object("revision",next,"previousRevision",baseRevision,"savedAt",Instant.now().toString(),"source",origin,"config",config);
            store.putState("prompts:version",Integer.toString(next),v);store.putState("prompts","revision",next);if(!"tool".equals(origin))store.coalesceEffect(EffectKind.NOTICE,"prompts",Map.of("text",object("prompt_config_saved",next,"effect","next-request","read","prompts/"+next).toString(),"revision",Integer.toString(next)),System.currentTimeMillis());return v;
        });
        host.promptsChanged(saved.path("revision").asInt());host.requestPass();
        return object("revision",saved.path("revision"),"effect","next-request","context_delivery",settings.deliveryState("prompts"));
    }
    public List<JsonNode> defaultChanged(JsonNode config) {
        List<JsonNode> changes=new ArrayList<>();config.path("overrides").fields().forEachRemaining(e->{JsonNode part=parts.get(e.getKey());String base=e.getValue().path("base").asText();if(part==null){changes.add(object("part",e.getKey(),"base",base,"removed",true));return;}if(!base.isEmpty()&&!base.equals(part.path("base").asText())){ObjectNode change=object("part",e.getKey(),"base",base);String prior=store.getState("prompts:builtin",base,String.class);if(prior!=null)change.put("previous",prior);changes.add(change);}});return changes;
    }
    public ObjectNode history(int offset,int limit) {
        int latest=revision(),start=Math.max(0,offset),size=Math.min(50,Math.max(1,limit));List<JsonNode> items=new ArrayList<>();
        for(int n=latest-start;n>=0&&items.size()<size;n--){ObjectNode summary=version(n);JsonNode config=summary.remove("config");List<String> changed=new ArrayList<>();if(summary.has("legacy")||(n>0&&version(n-1).has("legacy")))changed.add("legacy");else if(n>0)VersionedSettings.diff("",version(n-1).path("config"),config,changed);summary.set("changed",node(changed));items.add(summary);}
        return object("revisions",items,"next_offset",start+items.size()<=latest?start+items.size():null);
    }
    public ObjectNode view(Integer shown,int offset) {
        ObjectNode version=shown==null?current():version(shown);ObjectNode out=object("masterId",host.masterId(),"current",version,"revision",revision(),"defaults",defaults(),"sections",TEAM,"skillName","^[a-z][a-z0-9-]*$","builtin",List.of("github","feishu"),"skillPrefix",SKILL_PREFIX,"parts",parts.values(),"defaultChanged",defaultChanged(version.path("config")));
        out.setAll(history(offset,20));JsonNode sessions=host.sessions();if(sessions.isObject())out.setAll((ObjectNode)sessions);out.put("notificationPending",settings.deliveryState("prompts").equals("pending"));return out;
    }
    private String text(JsonNode config,String key) {JsonNode override=config.path("overrides").path(key).get("text");return override!=null?override.asText():parts.get(key).path("text").asText();}
    public String compose(String role,List<Map<String,String>> environment) {return compose(current().path("config"),role,environment);}
    public String compose(JsonNode config,String role,List<Map<String,String>> environment) {return sections(config,role,environment).stream().map(n->n.path("text").asText()).reduce((a,b)->a+"\n\n"+b).orElse("");}
    public List<JsonNode> sections(JsonNode config,String role,List<Map<String,String>> environment) {
        if(!Set.of("orchestrator","worker").contains(role))throw new ApiException(400,"Unknown prompt role");List<JsonNode> result=new ArrayList<>();
        for(String name:List.of("runtime/shared","runtime/"+role))result.add(section(name,"fixed",text(config,name)));
        Map<String,String> headings=Map.of("communication","Communication","scope","Ticket scope","delivery","Delivery requirements");for(String name:TEAM)if(!config.path(name).asText().isEmpty())result.add(section(name,"team","## "+headings.get(name)+"\n"+config.path(name).asText()));
        for(String name:List.of("subscriptions","replies",role.equals("orchestrator")?"dispatch":"work")){String key="runtime/"+name;result.add(section(key,"fixed",text(config,key)));}
        if(role.equals("orchestrator"))result.add(section("runtime/admission","fixed",text(config,"runtime/admission")));
        String handoff="runtime/handoff/"+role;result.add(section(handoff,"fixed",text(config,handoff)));
        String env=environment==null?"Execution environment: dsh-superagent is waiting for setup; the management panel names what is missing.":fill(text(config,"runtime/environment"),Map.of("variables",environment.stream().map(v->v.getOrDefault("key","")+"="+v.getOrDefault("value","")).reduce((a,b)->a+"\n"+b).orElse("")));
        result.add(section("runtime/environment","generated",env));
        if(role.equals("orchestrator")){List<String> rules=new ArrayList<>();settings.value("policy").path("rules").forEach(r->{if(r.path("enabled").asBoolean())rules.add("- "+line(r.path("name").asText())+" ("+r.path("id").asText()+"): "+line(r.path("instruction").asText()));});String key=rules.isEmpty()?"runtime/policy/none":"runtime/policy";result.add(section(key,"generated",fill(text(config,key),Map.of("version",settings.policyVersion(),"mode",settings.value("eval").path("mode").asText(),"rules",String.join("\n",rules)))));}
        return result;
    }
    private ObjectNode section(String name,String kind,String text) {return object("name",name,"key",name,"kind",kind,"text",text);}
    private static String line(String s) {return s.replaceAll("\\s*\\n\\s*"," ");}
    private static String fill(String template,Map<String,String> values) {Matcher m=Pattern.compile("\\{(\\w+)\\}").matcher(template);StringBuilder b=new StringBuilder();while(m.find())m.appendReplacement(b,Matcher.quoteReplacement(values.getOrDefault(m.group(1),m.group())));m.appendTail(b);return b.toString();}
    public Map<String,String> skills(JsonNode config) {Map<String,String> result=new LinkedHashMap<>();if(settings.value("source:feishu").path("enabled").asBoolean())result.put("feishu",text(config,"skill/feishu"));result.put("github",text(config,"skill/github"));config.path("skills").fields().forEachRemaining(e->result.put(e.getKey(),e.getValue().asText()));return result;}
    public Map<String,String> skills() {return skills(current().path("config"));}
    public ObjectNode preview(JsonNode input) {JsonNode config=validate(input);List<Map<String,String>> env=host.environment(null);return object("orchestrator",compose(config,"orchestrator",env),"worker",compose(config,"worker",env),"parts",object("orchestrator",sections(config,"orchestrator",env),"worker",sections(config,"worker",env)),"skills",skills(config),"scope","Workers additionally inherit the coding host prompt and repository AGENTS.md. Prompt edits never change tool permissions, the gate, or account wrappers.");}
    /** 模型调用开始时记录版本，权限检查允许自己的当前或曾使用版本。 */
    public void recordUse(String sessionId,int revision) {store.atomic(()->{ArrayNode used=store.getState("prompts:used",sessionId,ArrayNode.class);if(used==null)used=MAPPER.createArrayNode();boolean found=false;for(JsonNode n:used)if(n.asInt()==revision)found=true;if(!found)used.add(revision);store.putState("prompts:used",sessionId,used);store.putState("prompts:bound",sessionId,revision);return null;});}
    public int boundRevision(String sessionId) {Integer n=store.getState("prompts:bound",sessionId,Integer.class);if(n==null)throw new ApiException(403,"Session has not read a prompt revision");return n;}
    public boolean mayRead(String sessionId,int revision) {ArrayNode used=store.getState("prompts:used",sessionId,ArrayNode.class);if(used!=null)for(JsonNode n:used)if(n.asInt()==revision)return true;return false;}
}
