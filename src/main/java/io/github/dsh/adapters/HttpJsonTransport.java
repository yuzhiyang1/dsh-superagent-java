package io.github.dsh.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 小而明确的 HTTP 边界：不跟随重定向，不输出密钥/响应正文，重试交给持久化 effect 队列。 */
public final class HttpJsonTransport {
    public static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI base;
    private final String token;
    public HttpJsonTransport(URI base, String token) {
        boolean local = "localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost()) || "[::1]".equals(base.getHost());
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !("https".equals(base.getScheme()) || local && "http".equals(base.getScheme())))
            throw new IllegalArgumentException("API base must use HTTPS (HTTP permitted only for loopback tests)");
        this.base = URI.create(base.toString().replaceAll("/+$", "") + "/");
        this.token = token == null ? "" : token;
    }
    public JsonNode request(String method, String path, Object body, Map<String, String> headers)
            throws IOException, InterruptedException {
        if (path.startsWith("/") || path.contains("..") || path.contains("://")) throw new IllegalArgumentException("Relative API path required");
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json");
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        headers.forEach(builder::setHeader);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        // ofByteArray 在 headers 到达前不能限制体积；在流上读取上限，超限即关闭。
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new ApiException(response.statusCode());
            // HttpRequest.timeout 不足以约束 ofInputStream 后续读取，另设流读取截止。
            AtomicBoolean timedOut = new AtomicBoolean();
            Thread watchdog = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(60));
                    timedOut.set(true);
                    stream.close();
                } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                  catch (IOException ignored) { /* 主读取路径报告错误。 */ }
            });
            try {
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (timedOut.get()) throw new IOException("API response body timed out");
                if (bytes.length > MAX_BYTES) throw new IOException("API response exceeds 2 MiB");
                return bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes);
            } finally { watchdog.interrupt(); }
        }
    }
    public static final class ApiException extends IOException {
        private final int status;
        ApiException(int status) { super("Remote API returned HTTP " + status); this.status = status; }
        public int status() { return status; }
        public boolean retryable() { return status == 408 || status == 429 || status >= 500; }
    }
}
