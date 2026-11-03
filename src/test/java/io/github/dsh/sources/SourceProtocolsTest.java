package io.github.dsh.sources;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import io.github.dsh.adapters.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** 协议 fixture 走真正 HttpClient + SQLite；只替代远端服务，不替代领域和来源实现。 */
class SourceProtocolsTest {
    @TempDir Path dir; HttpServer server; URI base; SqliteStore store; Engine engine;SourceContext context;Clock clock;
    List<Request> requests=Collections.synchronizedList(new ArrayList<>());volatile Function<Request,Reply> handler;
    record Request(String method,String path,String query,JsonNode body,Headers headers){}
    record Reply(int status,Object body,Map<String,String> headers){Reply(Object body){this(200,body,Map.of());}}
    @BeforeEach void setup()throws Exception{
        clock=Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"),ZoneOffset.UTC);store=new SqliteStore(dir.resolve("state.db"));engine=new Engine(store,clock);context=new SourceContext(engine,store,clock);
        engine.setPolicy(new Policy("p1",AcceptMode.OBSERVE,Map.of("github",new SourcePolicy(Set.of("Alice"),Set.of("ci[bot]"),Map.of(),true),"feishu",new SourcePolicy(Set.of("ou_alice"),Set.of(),Map.of())),Set.of()));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{
            byte[] bytes=exchange.getRequestBody().readAllBytes();Request req=new Request(exchange.getRequestMethod(),exchange.getRequestURI().getPath(),Objects.toString(exchange.getRequestURI().getRawQuery(),""),bytes.length==0?HttpJsonTransport.JSON.nullNode():HttpJsonTransport.JSON.readTree(bytes),exchange.getRequestHeaders());requests.add(req);
            Reply reply=handler==null?new Reply(List.of()):handler.apply(req);reply.headers.forEach((k,v)->exchange.getResponseHeaders().set(k,v));
            byte[] data=reply.body instanceof byte[] raw?raw:HttpJsonTransport.JSON.writeValueAsBytes(reply.body);exchange.sendResponseHeaders(reply.status,reply.status==304?-1:data.length);if(reply.status!=304)exchange.getResponseBody().write(data);
        }catch(Throwable failure){byte[] body="fixture error".getBytes();try{exchange.sendResponseHeaders(500,body.length);exchange.getResponseBody().write(body);}catch(Exception ignored){}}finally{exchange.close();}});server.start();base=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
    }
    @AfterEach void close(){server.stop(0);store.close();}
    static ObjectNode node(Object...pairs){ObjectNode result=HttpJsonTransport.JSON.createObjectNode();for(int i=0;i<pairs.length;i+=2)result.set(pairs[i].toString(),HttpJsonTransport.JSON.valueToTree(pairs[i+1]));return result;}
    static ObjectNode issue(int n,boolean pr){ObjectNode value=node("id",n,"number",n,"title","Issue "+n,"body","@helper please","created_at","2026-11-03T00:00:00Z","updated_at","2026-11-03T00:00:00Z","html_url","https://github.com/team/repo/"+(pr?"pull/":"issues/")+n,"user",Map.of("login","alice","type","User"),"state","open");if(pr)value.set("pull_request",node("url","api"));return value;}
    static ObjectNode pull(int n,String actor,String branch,boolean merged){ObjectNode value=issue(n,true);value.set("user",node("login",actor,"type","User"));value.set("head",node("sha","1234567abcdef","ref",branch,"repo",Map.of("full_name","team/repo")));value.set("base",node("ref","main"));value.put("merged",merged);value.put("state",merged?"closed":"open");if(merged)value.put("merged_at","2026-11-03T00:01:00Z");return value;}
    static ObjectNode comment(int id,String body){return node("id",id,"body",body,"updated_at","2026-11-03T00:00:00Z","created_at","2026-11-03T00:00:00Z","html_url","https://github.com/team/repo/issues/1#issuecomment-"+id,"user",Map.of("login","alice","type","User"));}
    GitHubProtocol github(){return new GitHubProtocol(base,"","team/repo","github","helper",5);}
    GitHubSource source(boolean issues,boolean comments,boolean ci){return new GitHubSource("github",base,"",new GitHubSource.Config(true,false,"team/repo","helper",issues,false,comments,ci,60,5,3));}
    FeishuSource feishu(boolean writes){return new FeishuSource("feishu",base,"",new FeishuSource.Config(true,writes,"ou_bot","cli_app",List.of(new FeishuSource.Chat("oc_chat","Chat","all")),true,"none",60,2,5,10000,"oc_sync"));}
    static ObjectNode message(String id,long millis){return node("message_id",id,"chat_id","oc_chat","message_type","text","msg_type","text","content","{\"text\":\"hello\"}","body",Map.of("content","{\"text\":\"hello\"}"),"create_time",Long.toString(millis),"sender",Map.of("id","ou_alice","sender_type","user"));}
    static ObjectNode envelope(String eventId,String messageId){return node("header",Map.of("event_id",eventId,"event_type","im.message.receive_v1","app_id","cli_app","token","verify"),"event",Map.of("sender",Map.of("sender_id",Map.of("open_id","ou_alice"),"sender_type","user"),"message",message(messageId,Instant.parse("2026-11-03T00:00:00Z").toEpochMilli())));}
    static Reply data(Object value){return new Reply(Map.of("code",0,"data",value));}
    static Reply page(List<?> values,String next){return data(Map.of("items",values,"has_more",next!=null,"page_token",next==null?"":next));}
    String ticket(String source,String thread){engine.receive(new Trigger("origin-"+UUID.randomUUID(),source,List.of(new ThreadRef(source,thread)),source.equals("github")?"ALICE":"ou_alice",true,true,true,"comments",clock.instant().toString(),"work",new Channel(source,source+":"+thread,Map.of())));return engine.decide(engine.evaluateDue().getFirst().id(),Outcome.accept("work","scope",Set.of())).ticketId();}

    @Test void githubCommentNamespacesAreDistinctAndBodylessApprovalIsEvent(){
        var c=comment(7,"");c.put("state","APPROVED");c.put("submitted_at","2026-11-03T00:00:00Z");
        var review=github().comment(c,1,true,"review");c.put("body","text");var inline=github().comment(c,1,true,"inline");var normal=github().comment(c,1,true,"conversation");
        assertEquals(3,Set.of(review.eventId(),inline.eventId(),normal.eventId()).size());assertTrue(review.text().contains("APPROVED"));
    }
    @Test void notificationsHonorPollIntervalAndRepositoryBoundary(){
        handler=req->{if(req.path.equals("/notifications"))return new Reply(200,List.of(node("repository",Map.of("full_name","team/repo"),"subject",Map.of("url","https://api.github.com/repos/team/repo/issues/1")),node("repository",Map.of("full_name","other/repo"),"subject",Map.of("url","https://api.github.com/repos/other/repo/issues/99"))),Map.of("X-Poll-Interval","150"));if(req.path.endsWith("/issues/1"))return new Reply(issue(1,false));return new Reply(List.of(comment(9,"@helper hi")));};
        var result=source(false,false,false).poll(context);assertEquals(150,result.nextPollSeconds());assertTrue(result.errors().isEmpty(),result.errors().toString());assertEquals(2,store.list("trigger",Trigger.class).size());assertFalse(requests.stream().anyMatch(r->r.path.contains("99")));
    }
    @Test void failedNotificationPersistsRetryAndLaterRecovers(){
        AtomicBoolean failing=new AtomicBoolean(true);handler=req->{if(req.path.equals("/notifications"))return new Reply(List.of(node("repository",Map.of("full_name","team/repo"),"subject",Map.of("url","https://api.github.com/repos/team/repo/issues/1"))));if(req.path.endsWith("/issues/1"))return failing.get()?new Reply(404,Map.of(),Map.of()):new Reply(issue(1,false));return new Reply(List.of());};
        var first=source(false,false,false).poll(context);assertFalse(first.errors().isEmpty());assertTrue(context.state("github","notifications",JsonNode.class).path("retries").has("1"));
        failing.set(false);source(false,false,false).poll(context);assertFalse(context.state("github","notifications",JsonNode.class).path("retries").has("1"));assertNotNull(engine.trigger("github:issue:1"));
    }
    @Test void deletedNotificationDropsBut404DoesNot(){
        handler=req->req.path.equals("/notifications")?new Reply(List.of(node("repository",Map.of("full_name","team/repo"),"subject",Map.of("url","https://api.github.com/repos/team/repo/issues/1")))):new Reply(410,Map.of(),Map.of());
        var result=source(false,false,false).poll(context);assertTrue(result.errors().isEmpty());assertFalse(context.state("github","notifications",JsonNode.class).path("retries").has("1"));
    }
    @Test void staleNotificationRetryExpiresAfterOneDay(){
        context.state("github","notifications",node("since","2026-11-01T00:00:00Z","retries",Map.of("1",Map.of("since","2026-11-01T00:00:00Z","failedAt","2026-11-01T00:00:00Z","attempts",7,"pr",false))));
        handler=req->req.path.equals("/notifications")?new Reply(List.of()):new Reply(404,Map.of(),Map.of());source(false,false,false).poll(context);
        assertFalse(context.state("github","notifications",JsonNode.class).path("retries").has("1"));
    }
    @Test void repositoryStreamDoesNotIntakeBeforeEnableAndRetainsEtag(){
        AtomicInteger reads=new AtomicInteger();ObjectNode old=issue(1,false);old.put("created_at","2026-11-02T00:00:00Z");
        handler=req->{if(req.path.equals("/notifications"))return new Reply(List.of());if(reads.getAndIncrement()==0)return new Reply(200,List.of(old),Map.of("ETag","etag-1"));assertEquals("etag-1",req.headers.getFirst("If-None-Match"));return new Reply(304,null,Map.of());};
        var source=source(true,false,false);source.poll(context);source.poll(context);assertTrue(store.list("trigger",Trigger.class).isEmpty());assertEquals(2,reads.get());
    }
    @Test void ciBaselineFailureAndPendingRecoveryAreDifferent(){
        AtomicInteger phase=new AtomicInteger();handler=req->{if(req.path.equals("/notifications"))return new Reply(List.of());if(req.path.equals("/repos/team/repo"))return new Reply(Map.of("default_branch","main"));if(req.path.endsWith("/commits/main"))return new Reply(Map.of("sha","1234567abcdef"));if(req.path.endsWith("/check-runs"))return new Reply(Map.of("check_runs",List.of(Map.of("name","test","status",phase.get()==2?"in_progress":"completed","conclusion",phase.get()==1?"failure":phase.get()==2?"":"success"))));return new Reply(List.of());};
        var source=source(false,false,true);source.poll(context);assertTrue(store.list("trigger",Trigger.class).isEmpty());phase.set(1);source.poll(context);assertEquals(1,store.list("trigger",Trigger.class).size());phase.set(2);source.poll(context);assertEquals(1,store.list("trigger",Trigger.class).size());phase.set(3);source.poll(context);assertEquals(2,store.list("trigger",Trigger.class).size());
    }
    @Test void latestCommitStatusWinsAndChecksAreSorted()throws Exception{
        handler=req->req.path.endsWith("check-runs")?new Reply(Map.of("check_runs",List.of(Map.of("name","z","status","completed","conclusion","success"),Map.of("name","a","status","completed","conclusion","failure")))):new Reply(List.of(Map.of("context","lint","state","success"),Map.of("context","lint","state","failure")));
        var checks=github().checks("1234567abcdef");assertEquals(List.of("a"),checks.failures());assertEquals(1,checks.statuses().size());
    }
    @Test void pullBindingRejectsForeignOwnerAndClosesBoundTicket()throws Exception{
        String id=ticket("github","1");engine.setExecutionIdentity(id,"session","/tmp/work","agent/task");AtomicBoolean merged=new AtomicBoolean();handler=req->{if(req.path.equals("/notifications"))return new Reply(List.of());if(req.path.endsWith("/issues/1")||req.path.endsWith("/pulls/1"))return new Reply(pull(1,"helper","agent/task",merged.get()));if(req.path.endsWith("check-runs"))return new Reply(Map.of("check_runs",List.of()));return new Reply(List.of());};
        var source=source(false,false,false);source.claimPull(context,id,1);assertEquals(1,engine.ticket(id).follows.size());merged.set(true);source.poll(context);assertEquals("merged",engine.ticket(id).ended.outcome());
        String other=ticket("github","2");engine.setExecutionIdentity(other,"session2","/tmp/work2","agent/not-task");assertThrows(IllegalArgumentException.class,()->source.claimPull(context,other,1));
    }
    @Test void inlineReplyUsesRootAndDeletedTargetFallsBack()throws Exception{
        AtomicBoolean gone=new AtomicBoolean();AtomicReference<String> post=new AtomicReference<>();handler=req->{if(req.path.endsWith("/pulls/comments/9"))return gone.get()?new Reply(404,Map.of(),Map.of()):new Reply(node("id",9,"in_reply_to_id",3));if(req.method.equals("POST")){post.set(req.path);return new Reply(Map.of("id",40));}return new Reply(List.of());};
        github().send(1,"9","answer","outbox-1");assertTrue(post.get().endsWith("/pulls/1/comments/3/replies"));gone.set(true);github().send(1,"9","answer","outbox-2");assertTrue(post.get().endsWith("/issues/1/comments"));
    }
    @Test void readResourcesRejectTraversalAndExposePrInlineComments()throws Exception{
        handler=req->new Reply(List.of(comment(1,"text")));assertThrows(IllegalArgumentException.class,()->github().read("github/file/../secret"));assertTrue(requests.isEmpty());github().read("github/pulls/2/comments/1");assertEquals("/repos/team/repo/pulls/2/comments",requests.getFirst().path);
    }
    @Test void createDraftPrIsRealPostAndExistingHeadIsReused()throws Exception{
        AtomicInteger posts=new AtomicInteger();AtomicBoolean exists=new AtomicBoolean();handler=req->{if(req.method.equals("POST")){posts.incrementAndGet();assertTrue(req.body.path("draft").asBoolean());exists.set(true);ObjectNode p=pull(4,"helper","agent/task",false);p.put("draft",true);return new Reply(p);}return new Reply(exists.get()?List.of(pull(4,"helper","agent/task",false)):List.of());};
        github().createPull("Fix","body","agent/task","main",true);github().createPull("Fix","body","agent/task","main",true);assertEquals(1,posts.get());
    }
    @Test void githubWritesOffWaitAndSourceMissingWaits()throws Exception{
        var msg=new OutboxMessage("id","ticket",new Channel("github","github:1",Map.of("issue","1")),"hi","pending",null);assertEquals("waiting",source(false,false,false).send(context,msg).status());assertEquals("waiting",new SourceRegistry().send(context,msg).status());assertTrue(requests.isEmpty());
    }

    @Test void feishuRichTextImagesAndDirectMentionAreNormalized()throws Exception{
        var env=envelope("e1","om_one");((ObjectNode)env.path("event").path("message")).put("content","{\"zh_cn\":{\"title\":\"报告\",\"content\":[[{\"tag\":\"text\",\"text\":\"你好\"},{\"tag\":\"img\",\"image_key\":\"img_demo\"}]]}}");
        var source=feishu(false);assertTrue(source.receiveVerified(context,env));Trigger trigger=engine.trigger("feishu:message:om_one");assertTrue(trigger.text().contains("你好"));assertTrue(trigger.text().contains("img_demo"));assertEquals("oc_chat",trigger.scope());
    }
    @Test void feishuScopeFiltersUnknownChatsButDirectCanBeEnabled()throws Exception{
        var source=feishu(false);ObjectNode env=envelope("e1","om_one");((ObjectNode)env.path("event").path("message")).put("chat_id","oc_other");assertFalse(source.receiveVerified(context,env));((ObjectNode)env.path("event").path("message")).put("chat_type","p2p");assertTrue(source.receiveVerified(context,env));assertTrue(engine.trigger("feishu:message:om_one").addressed());
    }
    @Test void feishuBackfillUsesAtomicCursorAndRejectsStuckPages()throws Exception{
        long now=clock.millis();AtomicBoolean stuck=new AtomicBoolean(true);handler=req->page(List.of(message("om_new",now)),stuck.get()?"repeat":null);
        var source=feishu(false);context.state("feishu","history:oc_chat","2026-11-02T23:59:00Z");assertThrows(java.io.IOException.class,()->source.backfill(context,"oc_chat",false,clock.instant().minusSeconds(60),clock.instant()));
        assertNull(engine.trigger("feishu:message:om_new"));assertEquals("2026-11-02T23:59:00Z",context.state("feishu","history:oc_chat",String.class));
        stuck.set(false);source.backfill(context,"oc_chat",false,clock.instant().minusSeconds(60),clock.instant());assertNotNull(engine.trigger("feishu:message:om_new"));assertEquals(clock.instant().minusSeconds(5).toString(),context.state("feishu","history:oc_chat",String.class));
    }
    @Test void feishuHistoryRejectsCrossChatAndSkipsMalformedMessage()throws Exception{
        ObjectNode wrong=message("om_wrong",clock.millis());wrong.put("chat_id","oc_other");handler=req->page(List.of(wrong),null);assertThrows(java.io.IOException.class,()->feishu(false).backfill(context,"oc_chat",false,clock.instant().minusSeconds(60),clock.instant()));
        ObjectNode malformed=message("om_bad",clock.millis());malformed.remove("sender");handler=req->page(List.of(malformed,message("om_ok",clock.millis())),null);feishu(false).backfill(context,"oc_chat",false,clock.instant().minusSeconds(60),clock.instant());assertNull(engine.trigger("feishu:message:om_bad"));assertNotNull(engine.trigger("feishu:message:om_ok"));
    }
    @Test void feishuMemberReadDoesNotSilentlyMutatePolicyAndChecksPagination()throws Exception{
        String before=engine.policy().version();handler=req->page(List.of(Map.of("member_id","ou_new","name","New")),null);assertEquals(Map.of("ou_new","New"),feishu(false).readMembers());assertEquals(before,engine.policy().version());
        handler=req->page(List.of(Map.of("member_id","ou_new","name","New")),"stuck");assertThrows(java.io.IOException.class,()->feishu(false).readMembers());assertEquals(before,engine.policy().version());
    }
    @Test void feishuImageMustBelongToSourceMessageAndBeActualImage()throws Exception{
        var source=feishu(false);ObjectNode env=envelope("e1","om_one");((ObjectNode)env.path("event").path("message")).put("content","{\"image_key\":\"img_demo\"}");source.receiveVerified(context,env);
        ObjectNode original=message("om_one",clock.millis());original.set("body",node("content","{\"image_key\":\"img_demo\"}"));handler=req->req.path.contains("resources")?new Reply(new byte[]{(byte)137,80,78,71,13,10,26,10}):data(Map.of("items",List.of(original)));
        var image=(FeishuProtocol.ImageResource)source.read(context,"feishu/feishu:message:om_one/image/img_demo");assertEquals("image/png",image.mediaType());assertTrue(image.dataUrl().startsWith("data:image/png;base64,"));
        int before=requests.size();assertThrows(IllegalArgumentException.class,()->source.read(context,"feishu/feishu:message:om_one/image/img_other"));assertTrue(requests.subList(before,requests.size()).stream().noneMatch(r->r.path.contains("resources")));
    }
    @Test void feishuRemovedChatDropsReplyWhileDisabledSourceWaits()throws Exception{
        var source=feishu(true);var out=new OutboxMessage("id","ticket",new Channel("feishu","thread",Map.of("chatId","oc_removed","messageId","om_one")),"hello","pending",null);assertEquals("gone",source.send(context,out).status());assertTrue(requests.isEmpty());
    }
    @Test void feishuGoneErrorIsTerminalAndReplyHasStableUuid()throws Exception{
        AtomicBoolean gone=new AtomicBoolean();handler=req->{assertEquals(48,req.body.path("uuid").asText().length());assertEquals("post",req.body.path("msg_type").asText());return gone.get()?new Reply(Map.of("code",230011,"msg","withdrawn")):data(Map.of("message_id","om_reply"));};
        var out=new OutboxMessage("id:long:message","ticket",new Channel("feishu","thread",Map.of("chatId","oc_chat","messageId","om_one")),"hello","pending",null);assertEquals("sent",feishu(true).send(context,out).status());gone.set(true);assertEquals("gone",feishu(true).send(context,out).status());
    }
    @Test void feishuReadContextAnchorsChatAndRoot()throws Exception{
        var source=feishu(false);source.receiveVerified(context,envelope("e1","om_one"));ObjectNode original=message("om_one",clock.millis());original.put("root_id","om_root");handler=req->data(Map.of("items",List.of(req.path.endsWith("om_root")?message("om_root",clock.millis()):original)));
        JsonNode result=HttpJsonTransport.JSON.valueToTree(source.read(context,"feishu/feishu:message:om_one/root"));assertEquals("om_root",result.path("message").path("id").asText());assertThrows(IllegalArgumentException.class,()->source.read(context,"feishu/arbitrary/message"));
    }
    @Test void localTopicTimestampAndConsumerBackoffMatchBaseline(){assertNotNull(FeishuProtocol.time("2026-09-11 12:00"));assertEquals(1000,FeishuEventConsumer.retryMillis(null,0));assertEquals(2000,FeishuEventConsumer.retryMillis(1000L,1));assertEquals(60000,FeishuEventConsumer.retryMillis(60000L,0));assertEquals(1000,FeishuEventConsumer.retryMillis(60000L,60000));}
    private HttpResponse<String> push(FeishuWebhookReceiver receiver,JsonNode body,String timestamp,String signatureOverride)throws Exception {
        byte[] bytes=HttpJsonTransport.JSON.writeValueAsBytes(body);String nonce="fixture-nonce";
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update((timestamp+nonce+"encrypt-key").getBytes(StandardCharsets.UTF_8));
        String signature=signatureOverride==null?HexFormat.of().formatHex(digest.digest(bytes)):signatureOverride;
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+receiver.port()+"/feishu/events"))
                .header("X-Lark-Request-Timestamp",timestamp).header("X-Lark-Request-Nonce",nonce).header("X-Lark-Signature",signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void actualWebhookVerifiesSignaturePersistsReceiptAndDeduplicates()throws Exception {
        try(var receiver=new FeishuWebhookReceiver(new InetSocketAddress("127.0.0.1",0),feishu(false),context,"verify","encrypt-key")){
            receiver.start();String timestamp=Long.toString(clock.instant().getEpochSecond());var event=envelope("evt-one","om_one");
            assertEquals(200,push(receiver,event,timestamp,null).statusCode());assertEquals(200,push(receiver,event,timestamp,null).statusCode());
            assertEquals(1,store.list("trigger",Trigger.class).size());assertNotNull(context.state("feishu","event:evt-one",String.class));
        }
    }
    @Test void webhookRejectsForgedSignatureStaleTimestampWrongTokenAndWrongApp()throws Exception {
        try(var receiver=new FeishuWebhookReceiver(new InetSocketAddress("127.0.0.1",0),feishu(false),context,"verify","encrypt-key")){
            receiver.start();String timestamp=Long.toString(clock.instant().getEpochSecond());var event=envelope("evt-one","om_one");
            assertEquals(401,push(receiver,event,timestamp,"00").statusCode());
            assertEquals(401,push(receiver,event,Long.toString(clock.instant().minusSeconds(301).getEpochSecond()),null).statusCode());
            ((ObjectNode)event.path("header")).put("token","wrong");assertEquals(401,push(receiver,event,timestamp,null).statusCode());
            ((ObjectNode)event.path("header")).put("token","verify").put("app_id","other-app");assertEquals(401,push(receiver,event,timestamp,null).statusCode());
            assertTrue(store.list("trigger",Trigger.class).isEmpty());
        }
    }
    @Test void encryptedWebhookChallengeUsesRealAesCbc()throws Exception {
        byte[] iv=new byte[16];Arrays.fill(iv,(byte)7);var cipher=javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        byte[] key=MessageDigest.getInstance("SHA-256").digest("encrypt-key".getBytes(StandardCharsets.UTF_8));
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.IvParameterSpec(iv));
        byte[] encrypted=cipher.doFinal(HttpJsonTransport.JSON.writeValueAsBytes(Map.of("type","url_verification","token","verify","challenge","challenge-123")));
        byte[] packed=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,packed,0,iv.length);System.arraycopy(encrypted,0,packed,iv.length,encrypted.length);
        try(var receiver=new FeishuWebhookReceiver(new InetSocketAddress("127.0.0.1",0),feishu(false),context,"verify","encrypt-key")){
            receiver.start();var result=push(receiver,node("encrypt",Base64.getEncoder().encodeToString(packed)),Long.toString(clock.instant().getEpochSecond()),null);
            assertEquals(200,result.statusCode());assertEquals("challenge-123",HttpJsonTransport.JSON.readTree(result.body()).path("challenge").asText());
        }
    }
    @Test void webhookRefusesPublicBindAndMissingSecrets(){
        assertThrows(IllegalArgumentException.class,()->new FeishuWebhookReceiver(new InetSocketAddress("0.0.0.0",0),feishu(false),context,"verify","key"));
        assertThrows(IllegalArgumentException.class,()->new FeishuWebhookReceiver(new InetSocketAddress("127.0.0.1",0),feishu(false),context,"","key"));
    }
    @Test void cliConsumerReallyStartsReconnectsDeduplicatesAndStops()throws Exception {
        Path script=dir.resolve("fake-lark"),count=dir.resolve("runs.txt");
        String event=HttpJsonTransport.JSON.writeValueAsString(node("message_id","om_live","chat_id","oc_chat","chat_type","group","sender_id","ou_alice","sender_type","user","content","hello live","create_time",Long.toString(clock.millis())));
        Files.writeString(script,"#!/bin/sh\necho run >> '"+count+"'\necho '[event] ready' >&2\nprintf '%s\\n' '"+event+"'\n");
        Files.setPosixFilePermissions(script,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var cfg=feishu(false).configuration();var source=new FeishuSource("feishu",base,"",new FeishuSource.Config(cfg.enabled(),cfg.writesEnabled(),cfg.botId(),cfg.appId(),cfg.chats(),cfg.directMessages(),cfg.otherChats(),60,2,5,10000,"",script.toString()));
        try{source.start(context);long deadline=System.nanoTime()+Duration.ofSeconds(6).toNanos();while(System.nanoTime()<deadline){if(Files.exists(count)&&Files.readAllLines(count).size()>=3&&engine.trigger("feishu:message:om_live")!=null)break;Thread.sleep(30);}assertNotNull(engine.trigger("feishu:message:om_live"));assertTrue(Files.readAllLines(count).size()>=3);assertEquals(1,store.list("trigger",Trigger.class).size());}
        finally{source.close();}
        int stopped=Files.readAllLines(count).size();Thread.sleep(100);assertEquals(stopped,Files.readAllLines(count).size());
    }
    @Test void sourceHorizonIncludesRecentTicketEndAndExcludesOldIdleThread(){
        String id=ticket("github","1");engine.stopTicket(id);ThreadState thread=engine.threads().getFirst();
        assertTrue(context.polled(thread));var later=new SourceContext(engine,store,Clock.offset(clock,Duration.ofDays(15)));assertFalse(later.polled(thread));
    }

    private Path executable(String name,String body)throws Exception{
        Path path=dir.resolve(name);Files.writeString(path,"#!/bin/sh\n"+body);Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));return path;
    }
    @Test void ghWrapperOwnsAuthenticationAndPreservesStatusHeaders()throws Exception{
        Path wrapper=executable("gh-wrapper","case \"$2\" in\nuser) printf 'HTTP/2.0 200 OK\\nContent-Type: application/json\\n\\n{\"login\":\"helper\"}' ;;\nnotifications*) printf 'HTTP/2.0 304 Not Modified\\nX-Poll-Interval: 120\\nETag: fixture\\n\\n' ;;\n*) printf 'HTTP/2.0 404 Not Found\\n\\n{}'; exit 1 ;;\nesac\n");
        var api=new GitHubProtocol(base,"","team/repo","github","helper",5,wrapper.toString());assertEquals("helper",api.identity().path("login").asText());
        var response=api.detail("notifications?all=true",Map.of());assertEquals(304,response.status());assertEquals(List.of("120"),response.headers().get("x-poll-interval"));assertThrows(HttpJsonTransport.ApiException.class,()->api.get("issues/404"));assertTrue(requests.isEmpty());
    }
    @Test void larkWrapperRenderedMessagesThreadsAndBotIdentityNeedNoToken()throws Exception{
        String raw=HttpJsonTransport.JSON.writeValueAsString(message("om_context",clock.millis()).put("content","<forwarded_messages>child</forwarded_messages>").put("message_app_link","https://example.test/message"));
        Path wrapper=executable("lark-wrapper","case \"$2\" in\n+messages-mget) printf '%s' '{\"ok\":true,\"data\":{\"messages\":["+raw+"]}}' ;;\n+threads-messages-list) printf '%s' '{\"ok\":true,\"data\":{\"thread_id\":\"omt_thread\",\"messages\":["+raw+"],\"has_more\":false}}' ;;\n*) printf '%s' '{\"ok\":true,\"data\":{\"code\":0,\"bot\":{\"open_id\":\"ou_bot\"}}}' ;;\nesac\n");
        var api=new FeishuProtocol(base,"","feishu","ou_bot","cli_app",5,wrapper.toString());
        assertTrue(api.message("om_context","oc_chat").path("content").asText().contains("forwarded_messages"));assertEquals(1,api.history("thread","omt_thread","oc_chat",null,null,null,20,false).items().size());assertEquals("ou_bot",api.request("GET","bot/v3/info",null).path("open_id").asText());assertTrue(requests.isEmpty());
    }
    @Test void restMergedForwardExpandsReturnedChildrenWithoutCrossChatRead()throws Exception{
        ObjectNode root=message("om_root",clock.millis()).put("msg_type","merge_forward");ObjectNode child=message("om_child",clock.millis()).put("upper_message_id","om_root").put("chat_id","oc_original");
        handler=req->data(Map.of("items",List.of(root,child)));var api=new FeishuProtocol(base,"","feishu","ou_bot","cli_app",5);
        assertTrue(api.view(api.message("om_root","oc_chat")).get("text").toString().contains("ou_alice: hello"));assertEquals(1,requests.size());assertThrows(java.io.IOException.class,()->api.message("om_root","oc_other"));
    }
    @Test void sourceFindMatchesOriginalIdentifiers(){
        assertEquals(List.of("github:12"),source(false,false,false).find("https://github.com/elsewhere/repo/pull/12#comment").threads());assertEquals(List.of("github:12"),source(false,false,false).find(" #12 ").threads());assertEquals(List.of(),source(false,false,false).find("issue #12 unclear").threads());assertEquals(List.of("om_a","om_b"),feishu(false).find("om_a and om_b om_a").terms());
    }

    @Test void unicodeWordCountMatchesMixedLanguageAndRejectsLongImBeforeWrite()throws Exception{
        assertEquals(2,SourceText.wordCount("Hello, world!"));assertEquals(2,SourceText.wordCount("中文测试"));assertEquals(0,SourceText.wordCount("😀，！"));
        var api=new FeishuProtocol(base,"","feishu","ou_bot","cli_app",5);assertThrows(IllegalArgumentException.class,()->api.reply("om_a","word ".repeat(101),"id"));assertTrue(requests.isEmpty());
    }
    @Test void sourceContextWithoutTopicFallsBackToPrecedingChat()throws Exception{
        var source=feishu(false);source.receiveVerified(context,envelope("ev","om_anchor"));Trigger trigger=engine.trigger("feishu:message:om_anchor");
        handler=req->req.path.endsWith("/om_anchor")?data(Map.of("items",List.of(message("om_anchor",clock.millis())))):page(List.of(message("om_before",clock.millis()-1000)),null);
        var result=HttpJsonTransport.JSON.valueToTree(source.readContext(context,trigger,"thread",null));assertEquals("chat_neighbours",result.path("status").asText());assertEquals("om_before",result.path("messages").get(0).path("id").asText());assertTrue(requests.getLast().query.contains("page_size=6"));
    }

}
