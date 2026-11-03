package io.github.dsh.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsh.sources.*;
import java.net.URI;
import java.util.*;

/** 保存配置包含环境变量名；真正令牌仅在进程内交给 HTTP 边界。 */
public final class SourceFactory {
    private SourceFactory(){}
    public static SourceInstance create(String id,JsonNode value,ServiceOptions options){
        return create(id,value,options,io.github.dsh.adapters.HttpJsonTransport.JSON.createObjectNode());
    }
    public static SourceInstance create(String id,JsonNode value,ServiceOptions options,JsonNode policy){
        JsonNode c=value.path("config");boolean enabled=value.path("enabled").asBoolean(),writes=c.path("writesEnabled").asBoolean();
        if(id.equals("github")){
            String repository=c.path("repository").asText(),bot=c.path("botLogin").asText();if(repository.isBlank()||bot.isBlank())return null;
            return new GitHubSource(id,URI.create(c.path("apiBase").asText("https://api.github.com/")),options.secret(c.path("tokenEnvironment").asText("GITHUB_TOKEN")),
                    new GitHubSource.Config(enabled,writes,repository,bot,c.path("observeIssues").asBoolean(),c.path("observePulls").asBoolean(),c.path("observeComments").asBoolean(),c.path("observeCi").asBoolean(),c.path("pollSeconds").asInt(60),c.path("maxPages").asInt(100),3,c.path("cli").asText("")));
        }
        if(id.equals("feishu")){
            String bot=c.path("botId").asText(),app=c.path("appId").asText();if(bot.isBlank()||app.isBlank())return null;
            List<FeishuSource.Chat> chats=new ArrayList<>();for(JsonNode chat:c.path("chats"))chats.add(new FeishuSource.Chat(chat.path("chatId").asText(chat.path("id").asText()),chat.path("name").asText(""),chat.path("listen").asText("mentions")));
            return new FeishuSource(id,URI.create(c.path("apiBase").asText("https://open.feishu.cn/open-apis/")),options.secret(c.path("tokenEnvironment").asText("FEISHU_TENANT_ACCESS_TOKEN")),
                    new FeishuSource.Config(enabled,writes,bot,app,chats,c.path("directMessages").asBoolean(true),c.path("otherChats").asText("none"),c.path("pollSeconds").asInt(60),c.path("readPageSize").asInt(20),c.path("maxPages").asInt(100),10*1024*1024,policy.path("sources").path(id).path("syncChat").asText(""),c.path("cli").asText("")));
        }
        throw new IllegalArgumentException("Unknown built-in source kind");
    }
}
