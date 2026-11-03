package io.github.dsh.execution;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 可配置 HTTP 搜索适配器。抓取拒绝回环/内网和重定向；搜索服务通过标准 q 参数接入。 */
public final class HttpWebTools implements ExecutionSettings.WebTools {
    private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    private final URI searchEndpoint;private final Map<String,String> searchHeaders;
    public HttpWebTools(URI searchEndpoint,Map<String,String> searchHeaders){this.searchEndpoint=searchEndpoint;this.searchHeaders=Map.copyOf(searchHeaders);}
    @Override public String fetch(String url)throws Exception{return get(URI.create(url),Map.of());}
    @Override public String search(String query)throws Exception{
        if(searchEndpoint==null)throw new IOException("Search provider endpoint not configured");
        String separator=searchEndpoint.getQuery()==null?"?":"&";return get(URI.create(searchEndpoint+separator+"q="+URLEncoder.encode(query,StandardCharsets.UTF_8)),searchHeaders);
    }
    private String get(URI uri,Map<String,String> headers)throws Exception{
        if(!Set.of("http","https").contains(uri.getScheme())||uri.getUserInfo()!=null||uri.getHost()==null)throw new IOException("Public HTTP(S) URL required");
        for(InetAddress address:InetAddress.getAllByName(uri.getHost()))if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())throw new IOException("Private network destination refused");
        var b=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().header("Accept","text/plain,text/html,application/json");headers.forEach(b::header);
        var r=client.send(b.build(),HttpResponse.BodyHandlers.ofInputStream());try(var in=r.body()){
            if(r.statusCode()<200||r.statusCode()>299)throw new IOException("Web request HTTP "+r.statusCode());
            Thread watchdog=Thread.ofVirtual().start(()->{try{Thread.sleep(Duration.ofSeconds(30));in.close();}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(IOException ignored){}});
            try{byte[] bytes=in.readNBytes(256*1024+1);if(bytes.length>256*1024)throw new IOException("Web resource exceeds 256 KiB");return new String(bytes,StandardCharsets.UTF_8);}finally{watchdog.interrupt();}
        }
    }
}
