package io.github.dsh.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** GitHub Issues/PR 普通评论适配器。分页完整成功后才返回 cursor，调用方须与 intake 同事务提交。 */
public final class GitHubAdapter {
    public record PollResult(List<ReceivedEvent> events, Instant cursor) {
        public PollResult { events = List.copyOf(events); }
    }
    private static final Map<String, String> HEADERS = Map.of("Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28");
    private final HttpJsonTransport http;
    private final String sourceId, botLogin;
    private final boolean writesEnabled;
    public GitHubAdapter(URI apiBase, String token, String sourceId, String botLogin, boolean writesEnabled) {
        this.http = new HttpJsonTransport(apiBase, token);
        this.sourceId = Objects.requireNonNull(sourceId);
        if (botLogin == null || botLogin.isBlank()) throw new IllegalArgumentException("Bot login required to filter self messages");
        this.botLogin = botLogin;
        this.writesEnabled = writesEnabled;
    }
    private static String path(String repository, int issue) {
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || repository.contains("..") || issue < 1)
            throw new IllegalArgumentException("Expected owner/repository and positive issue number");
        return "repos/" + repository + "/issues/" + issue + "/comments";
    }
    public PollResult readIssueComments(String repository, int issue, Instant since) throws IOException, InterruptedException {
        String path = path(repository, issue);
        List<ReceivedEvent> events = new ArrayList<>();
        Instant cursor = since == null ? Instant.EPOCH : since;
        // since 是严格大于；重叠一秒可保护边界相同时间戳，由领域 Trigger ID 去重。
        String query = since == null ? "" : "&since=" + URLEncoder.encode(since.minusSeconds(1).toString(), StandardCharsets.UTF_8);
        for (int page = 1; page <= 100; page++) {
            JsonNode comments = http.request("GET", path + "?per_page=100&page=" + page + query, null, HEADERS);
            if (!comments.isArray()) throw new IOException("Expected GitHub comments array");
            for (JsonNode comment : comments) {
                String id = required(comment, "id");
                String time = required(comment, "updated_at");
                Instant at = Instant.parse(time);
                if (at.isAfter(cursor)) cursor = at;
                String actor = comment.path("user").path("login").asText("ghost");
                if (actor.equalsIgnoreCase(botLogin)) continue;
                String type = comment.path("user").path("type").asText("User");
                String text = comment.path("body").asText("");
                boolean bot = !"User".equals(type) || actor.endsWith("[bot]");
                events.add(new ReceivedEvent(sourceId, sourceId + ":comment:" + id + ":" + time,
                        sourceId + ":" + repository + ":" + issue, actor, bot, mentions(text, botLogin), text, at,
                        Map.of("repository", repository, "issue", Integer.toString(issue))));
            }
            if (comments.size() < 100) return new PollResult(events, cursor);
        }
        throw new IOException("GitHub pagination exceeded 100 pages; cursor not advanced");
    }
    public static boolean mentions(String text, String login) {
        return Pattern.compile("(?:^|[^a-zA-Z0-9_@])@" + Pattern.quote(login) + "(?![a-zA-Z0-9_-])", Pattern.CASE_INSENSITIVE)
                .matcher(text).find();
    }
    /** Marker 可缓解 POST 成功但本地 receipt 尚未提交的重发；不是跨进程 exactly-once。 */
    public String sendComment(String repository, int issue, String text, String deliveryId) throws IOException, InterruptedException {
        if (!writesEnabled) throw new IllegalStateException("External writes disabled; enable explicitly after review");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Reply text required");
        if (deliveryId == null || !deliveryId.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Safe stable delivery ID required");
        String path = path(repository, issue);
        String marker = "<!-- dsh-java-delivery:" + deliveryId + " -->";
        for (int page = 1; page <= 100; page++) {
            JsonNode comments = http.request("GET", path + "?per_page=100&page=" + page, null, HEADERS);
            if (!comments.isArray()) throw new IOException("Expected GitHub comments array");
            for (JsonNode comment : comments) {
                if (comment.path("user").path("login").asText().equalsIgnoreCase(botLogin)
                        && comment.path("body").asText().contains(marker)) return required(comment, "id");
            }
            if (comments.size() < 100) {
                var sent = http.request("POST", path, Map.of("body", text + "\n\n" + marker), HEADERS);
                return required(sent, "id");
            }
        }
        throw new IOException("Cannot verify delivery marker within pagination limit; not posting");
    }
    static String required(JsonNode node, String key) throws IOException {
        JsonNode value = node.path(key);
        if ((!value.isTextual() && !value.isNumber()) || value.asText().isBlank()) throw new IOException("Missing API field: " + key);
        return value.asText();
    }
}
