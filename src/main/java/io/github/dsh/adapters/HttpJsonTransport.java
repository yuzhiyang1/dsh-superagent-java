package io.github.dsh.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 小而明确的 HTTP 边界：不跟随重定向，不输出密钥/响应正文，重试交给持久化 effect 队列。 */
public final class HttpJsonTransport {
  public static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_BYTES = 2 * 1024 * 1024;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final URI base;
  private final String token;

  public HttpJsonTransport(URI base, String token) {
    boolean local =
        "localhost".equals(base.getHost())
            || "127.0.0.1".equals(base.getHost())
            || "[::1]".equals(base.getHost());
    if (base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null
        || !("https".equals(base.getScheme()) || local && "http".equals(base.getScheme())))
      throw new IllegalArgumentException(
          "API base must use HTTPS (HTTP permitted only for loopback tests)");
    this.base = URI.create(base.toString().replaceAll("/+$", "") + "/");
    this.token = token == null ? "" : token;
  }

  public JsonNode request(String method, String path, Object body, Map<String, String> headers)
      throws IOException, InterruptedException {
    Response response = requestDetailed(method, path, body, headers);
    return response.body();
  }

  public record Response(int status, Map<String, List<String>> headers, JsonNode body) {
    public String header(String key) {
      return headers.entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase(key))
          .flatMap(e -> e.getValue().stream())
          .findFirst()
          .orElse("");
    }
  }

  public record BinaryResponse(int status, Map<String, List<String>> headers, byte[] bytes) {}

  public Response requestDetailed(
      String method, String path, Object body, Map<String, String> headers)
      throws IOException, InterruptedException {
    BinaryResponse response = requestBytes(method, path, body, headers, MAX_BYTES);
    return new Response(
        response.status(),
        response.headers(),
        response.bytes().length == 0 ? JSON.nullNode() : JSON.readTree(response.bytes()));
  }

  public BinaryResponse requestBytes(
      String method, String path, Object body, Map<String, String> headers, int maxBytes)
      throws IOException, InterruptedException {
    if (maxBytes < 1 || maxBytes > 20 * 1024 * 1024)
      throw new IllegalArgumentException("Body limit must be 1..20MiB");
    if (path.startsWith("/") || path.contains("..") || path.contains("://"))
      throw new IllegalArgumentException("Relative API path required");
    var builder =
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json");
    if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
    headers.forEach(builder::setHeader);
    if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
    else
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    // ofByteArray 在 headers 到达前不能限制体积；在流上读取上限，超限即关闭。
    var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    try (var stream = response.body()) {
      if (response.statusCode() == 304)
        return new BinaryResponse(304, response.headers().map(), new byte[0]);
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new ApiException(response.statusCode());
      // HttpRequest.timeout 不足以约束 ofInputStream 后续读取，另设流读取截止。
      AtomicBoolean timedOut = new AtomicBoolean();
      Thread watchdog =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      Thread.sleep(Duration.ofSeconds(60));
                      timedOut.set(true);
                      stream.close();
                    } catch (InterruptedException expected) {
                      Thread.currentThread().interrupt();
                    } catch (IOException ignored) {
                      /* 主读取路径报告错误。 */
                    }
                  });
      try {
        byte[] bytes = stream.readNBytes(maxBytes + 1);
        if (timedOut.get()) throw new IOException("API response body timed out");
        if (bytes.length > maxBytes)
          throw new IOException(
              maxBytes == MAX_BYTES
                  ? "API response exceeds 2 MiB"
                  : "API response exceeds configured byte limit");
        return new BinaryResponse(response.statusCode(), response.headers().map(), bytes);
      } finally {
        watchdog.interrupt();
      }
    }
  }

  public static final class ApiException extends IOException {
    private final int status;

    public ApiException(int status) {
      super("Remote API returned HTTP " + status);
      this.status = status;
    }

    public int status() {
      return status;
    }

    public boolean retryable() {
      return status == 408 || status == 429 || status >= 500;
    }
  }
}
