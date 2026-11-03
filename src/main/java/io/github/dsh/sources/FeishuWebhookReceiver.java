package io.github.dsh.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import io.github.dsh.adapters.HttpJsonTransport;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 真正可启动的飞书事件入口。HTTPS 由反向代理终止；原始请求体签名、时钟窗口、app/token、去重均在接入前检查。
 * 对照飞书官方事件订阅协议，不依赖 lark-cli/Node 长连接进程。
 */
public final class FeishuWebhookReceiver implements AutoCloseable {
    private static final int MAX_BODY=1024*1024;
    private final HttpServer server;private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final FeishuSource source;private final SourceContext context;private final String token,key;private final Clock clock;
    public FeishuWebhookReceiver(InetSocketAddress bind,FeishuSource source,SourceContext context,String verificationToken,String encryptKey)throws IOException{
        if(verificationToken==null||verificationToken.isBlank()||encryptKey==null||encryptKey.isBlank())throw new IllegalArgumentException("Verification token and encrypt key required");
        if(bind.getAddress()==null||!bind.getAddress().isLoopbackAddress())throw new IllegalArgumentException("Bind receiver to loopback behind an authenticated HTTPS reverse proxy");
        this.source=source;this.context=context;this.token=verificationToken;this.key=encryptKey;clock=context.clock();
        server=HttpServer.create(bind,64);server.createContext("/feishu/events",this::handle);server.setExecutor(executor);
    }
    public void start(){server.start();}public int port(){return server.getAddress().getPort();}
    private void handle(HttpExchange exchange)throws IOException{
        int status=200;Object response=Map.of("code",0);
        try{
            if(!exchange.getRequestURI().getPath().equals("/feishu/events")||!exchange.getRequestMethod().equals("POST")){send(exchange,405,Map.of("error","POST required"));return;}
            byte[] raw=exchange.getRequestBody().readNBytes(MAX_BODY+1);if(raw.length>MAX_BODY){send(exchange,413,Map.of("error","body too large"));return;}
            JsonNode envelope=HttpJsonTransport.JSON.readTree(raw);if(envelope==null||!envelope.isObject())throw new IllegalArgumentException("Invalid JSON event");
            JsonNode event=envelope.hasNonNull("encrypt")?decrypt(envelope.path("encrypt").asText()):envelope;
            String eventToken=event.path("header").path("token").asText(event.path("token").asText());
            if(!equal(token,eventToken))throw new SecurityException("Token mismatch");
            boolean challenge="url_verification".equals(event.path("type").asText());
            // 配置时 challenge 可无签名头，但仍须正确的加密体和 verification token。
            String signature=exchange.getRequestHeaders().getFirst("X-Lark-Signature");
            if(!challenge||signature!=null)verify(raw,exchange.getRequestHeaders());
            else if(!envelope.hasNonNull("encrypt"))throw new SecurityException("Unsigned challenge must be encrypted");
            if(challenge){String value=event.path("challenge").asText();if(value.isBlank())throw new IllegalArgumentException("Challenge missing");response=Map.of("challenge",value);}
            else{
                String app=event.path("header").path("app_id").asText();if(!app.equals(source.configuration().appId()))throw new SecurityException("Wrong app");
                String eventId=event.path("header").path("event_id").asText();if(eventId.isBlank()||eventId.length()>200)throw new IllegalArgumentException("Event id missing/oversize");
                JsonNode verified=event;
                // 回执和领域 intake 原子提交；重复推送返回200，不重新交付。
                context.store().atomic(()->{
                    if(context.state(source.id(),"event:"+eventId,String.class)!=null)return null;
                    try{source.receiveVerified(context,verified);}catch(Exception e){throw new IllegalStateException("Event intake failed",e);}
                    context.state(source.id(),"event:"+eventId,clock.instant().toString());return null;
                });
            }
        }catch(SecurityException e){status=401;response=Map.of("error","event authentication failed");}
        catch(IllegalArgumentException e){status=400;response=Map.of("error","invalid event");}
        catch(Exception e){status=503;response=Map.of("error","event not committed; retry");}
        send(exchange,status,response);
    }
    private void verify(byte[] raw,Headers headers)throws Exception{
        String timestamp=headers.getFirst("X-Lark-Request-Timestamp"),nonce=headers.getFirst("X-Lark-Request-Nonce"),signature=headers.getFirst("X-Lark-Signature");
        if(timestamp==null||nonce==null||signature==null||nonce.length()>200)throw new SecurityException("Signature headers missing");
        long epoch;try{epoch=Long.parseLong(timestamp);}catch(NumberFormatException e){throw new SecurityException("Invalid timestamp");}
        if(Math.abs(clock.instant().getEpochSecond()-epoch)>300)throw new SecurityException("Event timestamp outside replay window");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update((timestamp+nonce+key).getBytes(StandardCharsets.UTF_8));String expected=HexFormat.of().formatHex(digest.digest(raw));
        if(!equal(expected,signature.toLowerCase(Locale.ROOT)))throw new SecurityException("Signature mismatch");
    }
    private JsonNode decrypt(String value)throws Exception{
        byte[] bytes;try{bytes=Base64.getDecoder().decode(value);}catch(IllegalArgumentException e){throw new SecurityException("Invalid ciphertext");}
        if(bytes.length<32||(bytes.length-16)%16!=0)throw new SecurityException("Invalid encrypted envelope");
        byte[] secret=MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret,"AES"),new IvParameterSpec(bytes,0,16));
        try{return HttpJsonTransport.JSON.readTree(cipher.doFinal(bytes,16,bytes.length-16));}catch(Exception e){throw new SecurityException("Event decryption failed");}
    }
    private static boolean equal(String expected,String actual){return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));}
    private static void send(HttpExchange exchange,int status,Object value)throws IOException{byte[] data=HttpJsonTransport.JSON.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(status,data.length);try(var output=exchange.getResponseBody()){output.write(data);}finally{exchange.close();}}
    @Override public void close(){server.stop(1);executor.shutdownNow();}
}
