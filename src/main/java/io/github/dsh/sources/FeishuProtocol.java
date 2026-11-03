package io.github.dsh.sources;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsh.adapters.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/** 官方飞书 REST 协议：读取、富文本转译、分页和消息内图片校验。 */
public final class FeishuProtocol {
    private final HttpJsonTransport http;private final String source,bot,app;private final int maxPages;private final String cli;
    public FeishuProtocol(URI base,String token,String source,String bot,String app,int maxPages){this(base,token,source,bot,app,maxPages,"");}
    public FeishuProtocol(URI base,String token,String source,String bot,String app,int maxPages,String cli){http=new HttpJsonTransport(base,token);this.source=source;this.bot=bot;this.app=app;this.maxPages=maxPages;this.cli=cli==null?"":cli;}
    private JsonNode command(List<String> args,java.nio.file.Path directory)throws Exception{
        List<String> full=new ArrayList<>(args);full.addAll(List.of("--as","bot","--format","json"));
        var result=SourceCommand.run(cli,full,directory,2*1024*1024,java.time.Duration.ofSeconds(60));
        JsonNode response=HttpJsonTransport.JSON.readTree(result.output());
        if(response==null||!response.path("ok").asBoolean())throw new ApiException(response==null?-1:response.path("error").path("code").asInt(-1),response==null?"":response.path("error").path("message").asText());
        if(result.exit()!=0)throw new IOException("Source CLI exited "+result.exit());return response.path("data");
    }
    public static final class ApiException extends IOException{
        private final int code;private final boolean gone;
        ApiException(int code,String message){super("Feishu application error "+code);this.code=code;this.gone=code==230011||(code/100==2300&&(message.toLowerCase(Locale.ROOT).contains("not found")||message.toLowerCase(Locale.ROOT).contains("withdrawn")));}
        public int code(){return code;}public boolean gone(){return gone;}
    }
    public JsonNode request(String method,String path,Object payload)throws Exception{
        JsonNode result;
        if(!cli.isBlank()){
            int split=path.indexOf('?');String endpoint=split<0?path:path.substring(0,split);List<String> args=new ArrayList<>(List.of("api",method,"/open-apis/"+endpoint));
            if(split>=0){Map<String,String> params=new LinkedHashMap<>();for(String pair:path.substring(split+1).split("&")){String[] bits=pair.split("=",2);params.put(java.net.URLDecoder.decode(bits[0],StandardCharsets.UTF_8),java.net.URLDecoder.decode(bits.length>1?bits[1]:"",StandardCharsets.UTF_8));}args.addAll(List.of("--params",HttpJsonTransport.JSON.writeValueAsString(params)));}
            if(payload!=null)args.addAll(List.of("--body",HttpJsonTransport.JSON.writeValueAsString(payload)));
            result=command(args,null);if(!result.has("code"))return result;
        }else result=http.request(method,path,payload,Map.of());
        if(!result.path("code").canConvertToInt())throw new IOException("Feishu response missing application code");
        if(result.path("code").asInt()!=0)throw new ApiException(result.path("code").asInt(),result.path("msg").asText());
        return result.has("data")?result.path("data"):result.path("bot");
    }
    public record Page(List<JsonNode> items,String next,int skipped){public Page{items=List.copyOf(items);}public Page(List<JsonNode> items,String next){this(items,next,0);}}
    public Page page(String path,String cursor,int size)throws Exception{
        String query=path+(path.contains("?")?"&":"?")+"page_size="+size+(cursor==null?"":"&page_token="+GitHubProtocol.encode(cursor));
        JsonNode data=request("GET",query,null);JsonNode items=data.path("items");if(!items.isArray())throw new IOException("Feishu page missing items");
        List<JsonNode> list=new ArrayList<>();items.forEach(list::add);String next=data.path("has_more").asBoolean()?data.path("page_token").asText():null;
        if(next!=null&&(next.isBlank()||next.equals(cursor)))throw new IOException("Feishu pagination did not advance");return new Page(list,next);
    }
    public List<JsonNode> all(String path,int size)throws Exception{
        List<JsonNode> items=new ArrayList<>();Set<String> seen=new HashSet<>();String cursor=null;
        for(int page=0;page<maxPages;page++){Page result=page(path,cursor,size);items.addAll(result.items());cursor=result.next();if(cursor==null)return items;if(!seen.add(cursor))throw new IOException("Feishu pagination cycle");}
        throw new IOException("Feishu page limit; checkpoint unchanged");
    }
    public JsonNode message(String messageId,String expectedChat)throws Exception{
        safe(messageId,"om_");JsonNode data=cli.isBlank()?request("GET","im/v1/messages/"+messageId,null):command(List.of("im","+messages-mget","--message-ids",messageId,"--no-reactions"),null);
        JsonNode found=null;for(JsonNode item:data.path(cli.isBlank()?"items":"messages"))if(item.path("message_id").asText().equals(messageId))found=item;
        if(found==null)throw new IOException("Requested message unavailable");
        if(!found.path("chat_id").asText().equals(expectedChat))throw new IOException("Message outside source chat");
        if(cli.isBlank()&&"merge_forward".equals(found.path("msg_type").asText())){
            Map<String,List<JsonNode>> children=new LinkedHashMap<>();for(JsonNode item:data.path("items"))if(item.hasNonNull("upper_message_id"))children.computeIfAbsent(item.path("upper_message_id").asText(),unused->new ArrayList<>()).add(item);
            StringBuilder rendered=new StringBuilder("<forwarded_messages>\n");appendForward(messageId,children,new HashSet<>(),rendered,0);rendered.append("</forwarded_messages>");
            com.fasterxml.jackson.databind.node.ObjectNode expanded=((com.fasterxml.jackson.databind.node.ObjectNode)found).deepCopy();expanded.put("content",rendered.toString());expanded.set("body",HttpJsonTransport.JSON.valueToTree(Map.of("content",HttpJsonTransport.JSON.writeValueAsString(Map.of("text",rendered.toString())))));found=expanded;
        }
        return found;
    }
    private static void appendForward(String parent,Map<String,List<JsonNode>> children,Set<String> seen,StringBuilder text,int depth)throws IOException{
        if(depth>16||seen.size()>1000)throw new IOException("Forwarded message graph exceeds limit");
        for(JsonNode item:children.getOrDefault(parent,List.of())){String id=item.path("message_id").asText();if(!seen.add(id))throw new IOException("Forwarded message graph cycle");
            text.append(item.path("sender").path("name").asText(item.path("sender").path("id").asText())).append(": ").append(render(item.path("body").path("content").asText(item.path("content").asText()))).append('\n');
            if(text.length()>1024*1024)throw new IOException("Forwarded message text exceeds limit");appendForward(id,children,seen,text,depth+1);
        }
    }
    public Page history(String kind,String container,String chat,Instant since,Instant until,String cursor,int size,boolean descending)throws Exception{
        if(!Set.of("chat","thread").contains(kind))throw new IllegalArgumentException("Invalid container type");safe(container,kind.equals("chat")?"oc_":"om");
        String path="im/v1/messages?container_id_type="+kind+"&container_id="+GitHubProtocol.encode(container)+"&sort_type="+(descending?"ByCreateTimeDesc":"ByCreateTimeAsc");
        if(since!=null)path+="&start_time="+since.getEpochSecond();if(until!=null)path+="&end_time="+(until.toEpochMilli()+999)/1000;
        Page result;
        if(!cli.isBlank()&&kind.equals("thread")&&since==null&&until==null){
            List<String> args=new ArrayList<>(List.of("im","+threads-messages-list","--thread",container,"--order",descending?"desc":"asc","--page-size",Integer.toString(size),"--no-reactions"));if(cursor!=null)args.addAll(List.of("--page-token",cursor));
            JsonNode data=command(args,null);if(!data.path("thread_id").asText().equals(container)||!data.path("messages").isArray())throw new IOException("Thread page mismatch");
            List<JsonNode> list=new ArrayList<>();data.path("messages").forEach(list::add);String next=data.path("has_more").asBoolean()?data.path("page_token").asText():null;
            if(next!=null&&(next.isBlank()||next.equals(cursor)))throw new IOException("Thread pagination did not advance");result=new Page(list,next);
        }else result=page(path,cursor,size);
        List<JsonNode> valid=new ArrayList<>();int skipped=0;
        for(JsonNode item:result.items()){
            try{required(item,"message_id");required(item,"chat_id");time(required(item,"create_time"));JsonNode sender=item.path("sender");if(sender.path("id").asText(sender.path("open_bot_id").asText()).isBlank()||!sender.path("sender_type").isTextual())throw new IOException("Sender missing");}
            catch(IOException|IllegalArgumentException malformed){skipped++;continue;}
            if(!item.path("chat_id").asText().equals(chat)||(kind.equals("thread")&&item.hasNonNull("thread_id")&&!item.path("thread_id").asText().equals(container)))throw new IOException("History outside requested chat/thread");valid.add(item);
        }
        return new Page(valid,result.next(),skipped);
    }
    public ReceivedEvent event(JsonNode envelope)throws IOException{
        if(!envelope.path("header").path("event_type").asText().equals("im.message.receive_v1"))return null;
        JsonNode event=envelope.path("event"),message=event.path("message"),sender=event.path("sender");
        String actor=sender.path("sender_id").path("open_id").asText(sender.path("sender_id").path("app_id").asText());
        return normalize(message,actor,sender.path("sender_type").asText(),"p2p".equals(message.path("chat_type").asText()),null);
    }
    public ReceivedEvent normalizedEvent(JsonNode value)throws IOException {
        return normalize(value,required(value,"sender_id"),required(value,"sender_type"),"p2p".equals(value.path("chat_type").asText()),null);
    }
    public ReceivedEvent historical(JsonNode message,boolean direct,String fallbackRoot)throws IOException{
        JsonNode sender=message.path("sender");String actor=sender.path("open_bot_id").asText(sender.path("id").asText(sender.path("sender_id").path("open_id").asText()));
        return normalize(message,actor,sender.path("sender_type").asText(),direct,fallbackRoot);
    }
    private ReceivedEvent normalize(JsonNode message,String actor,String senderType,boolean direct,String fallbackRoot)throws IOException{
        if(actor.equals(bot)||actor.equals(app)||message.path("deleted").asBoolean())return null;
        if(actor.isBlank())throw new IOException("Message actor missing");
        String messageId=required(message,"message_id"),chat=required(message,"chat_id");safe(messageId,"om_");safe(chat,"oc_");
        String raw=message.path("content").asText(message.path("body").path("content").asText());String text=render(raw);
        boolean addressed=direct;
        for(JsonNode mention:message.path("mentions")){String id=mention.path("id").isTextual()?mention.path("id").asText():mention.path("id").path("open_id").asText();if(id.equals(bot)||id.equals(app))addressed=true;String key=mention.path("key").asText();if(!key.isBlank())text=text.replace(key,"@"+mention.path("name").asText());}
        String root=message.path("root_id").asText("");if(root.isBlank())root=fallbackRoot==null||fallbackRoot.isBlank()?messageId:fallbackRoot;
        String parent=message.path("parent_id").asText("");String thread=message.path("thread_id").asText("");
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("chatId",chat);metadata.put("messageId",messageId);metadata.put("direct",direct);metadata.put("rootId",root);metadata.put("sourceTriggerId",source+":message:"+messageId);
        if(!parent.isBlank())metadata.put("parentId",parent);if(!thread.isBlank())metadata.put("threadId",thread);metadata.put("messageType",message.path("message_type").asText(message.path("msg_type").asText()));
        Map<String,String> address=Map.of("chatId",chat,"messageId",messageId,"direct",Boolean.toString(direct));
        return new ReceivedEvent(source,source+":message:"+messageId,source+":"+chat+":"+root,actor,!senderType.equals("user"),addressed,text,time(required(message,"create_time")),address,metadata,message.path("message_app_link").asText(null),parent.isBlank()?null:source+":message:"+parent);
    }
    /** 保留 image_key 和合并转发中的引用，文本不借用跨来源身份。 */
    public static String render(String content)throws IOException{
        if(content==null||content.isBlank())return "";JsonNode node;
        try{node=HttpJsonTransport.JSON.readTree(content);}catch(IOException plain){return content;}
        StringBuilder out=new StringBuilder();renderNode(node,out);return out.toString().strip();
    }
    private static void renderNode(JsonNode node,StringBuilder out){
        if(node==null||node.isNull())return;if(node.isTextual()){out.append(node.asText()).append(' ');return;}
        if(node.isArray()){for(JsonNode child:node){renderNode(child,out);if(child.isArray())out.append('\n');}return;}
        if(node.isObject()){
            if(node.has("text")){out.append(node.path("text").asText()).append(' ');if(node.has("href"))out.append('(').append(node.path("href").asText()).append(") ");}
            if(node.has("image_key"))out.append("[image ").append(node.path("image_key").asText()).append("] ");
            if(node.has("file_key"))out.append("[file ").append(node.path("file_key").asText()).append("] ");
            if(node.has("title"))out.append(node.path("title").asText()).append('\n');
            if(node.has("user_id"))out.append('@').append(node.path("user_id").asText()).append(' ');
            node.fields().forEachRemaining(e->{if(!Set.of("text","href","image_key","file_key","title","user_id","tag","style").contains(e.getKey())&&(e.getValue().isContainerNode()||e.getKey().equals("message_id")))renderNode(e.getValue(),out);});
        }
    }
    public static List<String> imageKeys(String text){Matcher m=Pattern.compile("\\bimg_[A-Za-z0-9_-]+\\b").matcher(text);Set<String> keys=new LinkedHashSet<>();while(m.find())keys.add(m.group());return List.copyOf(keys);}
    public String link(String messageId,String chat)throws Exception{
        JsonNode value=message(messageId,chat);String link=value.path("message_app_link").asText();if(value.path("deleted").asBoolean()||link.isBlank())throw new IOException("Source message link unavailable");return link;
    }
    public record ImageResource(String messageId,String imageKey,String mediaType,String dataUrl,int bytes){}
    public ImageResource image(String messageId,String chat,String imageKey,int maxBytes)throws Exception{
        JsonNode message=message(messageId,chat);String rendered=render(cli.isBlank()?message.path("body").path("content").asText(message.path("content").asText()):message.path("content").asText(message.path("body").path("content").asText()));
        if(!imageKeys(rendered).contains(imageKey))throw new IllegalArgumentException("Image key is not in the anchored source message");
        byte[] data;
        if(cli.isBlank())data=http.requestBytes("GET","im/v1/messages/"+messageId+"/resources/"+GitHubProtocol.encode(imageKey)+"?type=image",null,Map.of(),maxBytes).bytes();
        else{
            java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("dsh-feishu-image-");
            try{
                JsonNode result=command(List.of("im","+messages-resources-download","--message-id",messageId,"--file-key",imageKey,"--type","image","--output","./image"),directory);
                java.nio.file.Path saved=java.nio.file.Path.of(required(result,"saved_path"));if(!saved.isAbsolute())saved=directory.resolve(saved);saved=saved.toRealPath();
                if(!saved.getParent().equals(directory.toRealPath())||!java.nio.file.Files.isRegularFile(saved)||java.nio.file.Files.size(saved)>maxBytes)throw new IOException("Downloaded image outside allowed directory or byte limit");
                data=java.nio.file.Files.readAllBytes(saved);
            }finally{try(var files=java.nio.file.Files.list(directory)){for(var file:files.toList())java.nio.file.Files.deleteIfExists(file);}java.nio.file.Files.deleteIfExists(directory);}
        }String type=mediaType(data);return new ImageResource(messageId,imageKey,type,"data:"+type+";base64,"+Base64.getEncoder().encodeToString(data),data.length);
    }
    private static String mediaType(byte[] data)throws IOException{
        if(data.length>=8&&data[0]==(byte)137&&data[1]==80&&data[2]==78&&data[3]==71)return "image/png";
        if(data.length>=3&&data[0]==(byte)255&&data[1]==(byte)216&&data[2]==(byte)255)return "image/jpeg";
        if(data.length>=6&&new String(data,0,6,StandardCharsets.US_ASCII).matches("GIF8[79]a"))return "image/gif";
        if(data.length>=12&&new String(data,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(data,8,4,StandardCharsets.US_ASCII).equals("WEBP"))return "image/webp";
        throw new IOException("Resource is not PNG/JPEG/GIF/WebP");
    }
    public String reply(String messageId,String text,String stableId)throws Exception{
        safe(messageId,"om_");SourceText.checkedImText(text);if(text.isBlank()||text.length()>30000)throw new IllegalArgumentException("Reply must be 1..30000 characters");
        if(!cli.isBlank()){JsonNode sent=command(List.of("im","+messages-reply","--message-id",messageId,"--markdown",text,"--reply-in-thread","--idempotency-key",AdapterChannelSender.deliveryId(stableId)),null);return sent.path("message_id").asText("cli-confirmed");}
        Object post=Map.of("zh_cn",Map.of("title","","content",List.of(List.of(Map.of("tag","md","text",text)))));
        JsonNode data=request("POST","im/v1/messages/"+messageId+"/reply",Map.of("msg_type","post","content",HttpJsonTransport.JSON.writeValueAsString(post),"reply_in_thread",true,"uuid",AdapterChannelSender.deliveryId(stableId)));
        return required(data,"message_id");
    }
    public Map<String,Object> view(JsonNode message)throws IOException{
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",message.path("message_id").asText());JsonNode sender=message.path("sender");result.put("from",sender.path("name").asText(sender.path("id").asText()));
        result.put("at",time(message.path("create_time").asText()).toString());String text=message.path("deleted").asBoolean()?"":render(cli.isBlank()?message.path("body").path("content").asText(message.path("content").asText()):message.path("content").asText(message.path("body").path("content").asText()));
        for(JsonNode mention:message.path("mentions")){String key=mention.path("key").asText();if(!key.isBlank())text=text.replace(key,"@"+mention.path("name").asText(mention.path("id").path("open_id").asText()));}result.put("text",text);if(!sender.path("sender_type").asText().equals("user"))result.put("bot",true);if(message.path("deleted").asBoolean())result.put("deleted",true);
        if(message.hasNonNull("parent_id"))result.put("reply_to",message.path("parent_id").asText());if(message.hasNonNull("message_app_link"))result.put("url",message.path("message_app_link").asText());return result;
    }
    public static Instant time(String value){
        if(value.matches("[0-9]+"))return Instant.ofEpochMilli(Long.parseLong(value));
        try{return Instant.parse(value);}catch(java.time.format.DateTimeParseException iso){
            // lark-cli topic pages may use local wall time; use deployment JVM zone, same convention as upstream Date.parse.
            return java.time.LocalDateTime.parse(value,java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")).atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
    }
    static String required(JsonNode node,String key)throws IOException{String value=node.path(key).asText();if(value.isBlank())throw new IOException("Missing Feishu field "+key);return value;}
    static void safe(String value,String prefix){if(value==null||!value.startsWith(prefix)||!value.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Invalid Feishu identifier");}
}
