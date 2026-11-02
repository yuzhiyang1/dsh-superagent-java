package io.github.dsh.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** 飞书 im.message.receive_v1 -> Source DTO。调用方必须先验证 webhook 签名/令牌，本类不是公网 webhook server。 */
public final class FeishuAdapter {
    private final HttpJsonTransport http;
    private final String sourceId, botId, appId;
    private final boolean writesEnabled;
    public FeishuAdapter(URI apiBase, String tenantAccessToken, String sourceId, String botId, String appId, boolean writesEnabled) {
        http = new HttpJsonTransport(apiBase, tenantAccessToken);
        this.sourceId = Objects.requireNonNull(sourceId);
        if (botId == null || botId.isBlank() || appId == null || appId.isBlank()) throw new IllegalArgumentException("Bot and app identity required");
        this.botId = botId;
        this.appId = appId;
        this.writesEnabled = writesEnabled;
    }
    public Optional<ReceivedEvent> parseEvent(String verifiedEventJson) throws IOException {
        JsonNode root = HttpJsonTransport.JSON.readTree(verifiedEventJson);
        if (!"im.message.receive_v1".equals(root.path("header").path("event_type").asText())) return Optional.empty();
        JsonNode event = root.path("event"), message = event.path("message"), sender = event.path("sender");
        String actor = GitHubAdapter.required(sender.path("sender_id"), "open_id");
        if (actor.equals(botId) || actor.equals(appId)) return Optional.empty();
        if (!"text".equals(message.path("message_type").asText())) return Optional.empty(); // 图片/富文本明确不支持。
        String messageId = GitHubAdapter.required(message, "message_id");
        String chatId = GitHubAdapter.required(message, "chat_id");
        String content = GitHubAdapter.required(message, "content");
        String text = GitHubAdapter.required(HttpJsonTransport.JSON.readTree(content), "text");
        boolean addressed = "p2p".equals(message.path("chat_type").asText());
        for (JsonNode mention : message.path("mentions")) {
            String id = mention.path("id").path("open_id").asText();
            if (id.equals(botId) || id.equals(appId)) addressed = true;
            String key = mention.path("key").asText();
            if (!key.isBlank()) text = text.replace(key, "@" + mention.path("name").asText());
        }
        String rootId = message.path("root_id").asText("");
        if (rootId.isBlank()) rootId = messageId;
        Instant at;
        try { at = Instant.ofEpochMilli(Long.parseLong(GitHubAdapter.required(message, "create_time"))); }
        catch (NumberFormatException ex) { throw new IOException("Invalid Feishu message timestamp", ex); }
        return Optional.of(new ReceivedEvent(sourceId, sourceId + ":message:" + messageId,
                sourceId + ":" + chatId + ":" + rootId, actor, !"user".equals(sender.path("sender_type").asText()),
                addressed, text, at, Map.of("messageId", messageId, "chatId", chatId)));
    }
    public String reply(String messageId, String text, String deliveryId) throws IOException, InterruptedException {
        if (!writesEnabled) throw new IllegalStateException("External writes disabled; enable explicitly after review");
        if (messageId == null || !messageId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid message ID");
        if (deliveryId == null || !deliveryId.matches("[A-Za-z0-9_-]{1,50}")) throw new IllegalArgumentException("Stable delivery ID (<=50 chars) required");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Reply text required");
        var result = http.request("POST", "im/v1/messages/" + messageId + "/reply",
                Map.of("msg_type", "text", "content", HttpJsonTransport.JSON.writeValueAsString(Map.of("text", text)),
                        "uuid", deliveryId, "reply_in_thread", true), Map.of());
        if (!result.path("code").canConvertToInt() || result.path("code").asInt() != 0) throw new IOException("Feishu reply rejected (application code " + result.path("code").asText("missing") + ")");
        return GitHubAdapter.required(result.path("data"), "message_id");
    }
}
