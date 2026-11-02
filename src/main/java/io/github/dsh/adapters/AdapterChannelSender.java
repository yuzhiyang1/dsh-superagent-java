package io.github.dsh.adapters;

import io.github.dsh.core.Model.OutboxMessage;
import io.github.dsh.runtime.ChannelSender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 按 Source 实例路由，不按消息正文猜目的地；客户端仍保留独立的禁写开关。 */
public final class AdapterChannelSender implements ChannelSender {
    private final Map<String, GitHubAdapter> github;
    private final Map<String, FeishuAdapter> feishu;
    public AdapterChannelSender(Map<String, GitHubAdapter> github, Map<String, FeishuAdapter> feishu) {
        this.github = Map.copyOf(github); this.feishu = Map.copyOf(feishu);
        if (github.keySet().stream().anyMatch(feishu::containsKey)) throw new IllegalArgumentException("Source registered twice");
    }
    @Override public String send(OutboxMessage message) throws Exception {
        String source = message.channel().source();
        Map<String, String> address = message.channel().address();
        String deliveryId = deliveryId(message.id());
        if (github.containsKey(source)) return github.get(source).sendComment(required(address, "repository"),
                Integer.parseInt(required(address, "issue")), message.text(), deliveryId);
        if (feishu.containsKey(source)) return feishu.get(source).reply(required(address, "messageId"), message.text(), deliveryId);
        throw new IllegalStateException("No channel registered for source " + source);
    }
    /** 192-bit 稳定摘要：48 个 hex 字符，满足飞书 uuid 的 50 字符限制，不泄漏内部 id。 */
    public static String deliveryId(String messageId) {
        Objects.requireNonNull(messageId);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(messageId.getBytes(StandardCharsets.UTF_8)), 0, 24); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String required(Map<String, String> address, String key) {
        String value = address.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing channel address " + key);
        return value;
    }
}
