package io.github.dsh.service;

import io.github.dsh.execution.ExecutionSettings;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 真正的受限大小 HTTP 文本读取；搜索端点可配置，默认返回 DuckDuckGo 的实际搜索页。 */
public final class ConfiguredWebTools implements ExecutionSettings.WebTools {
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();
  private final String search;

  public ConfiguredWebTools(String searchEndpoint) {
    search = searchEndpoint;
  }

  @Override
  public String fetch(String url) throws Exception {
    URI uri = URI.create(url);
    if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null)
      throw new IllegalArgumentException("Web reads require HTTPS without credentials");
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "DSH-Superagent-Java/1.0")
            .GET()
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (var body = response.body()) {
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new java.io.IOException("Web HTTP " + response.statusCode());
      Thread deadline =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      Thread.sleep(Duration.ofSeconds(30));
                      body.close();
                    } catch (InterruptedException interrupted) {
                      Thread.currentThread().interrupt();
                    } catch (java.io.IOException ignored) {
                    }
                  });
      try {
        byte[] bytes = body.readNBytes(1_000_001);
        if (bytes.length > 1_000_000) throw new java.io.IOException("Web body exceeds1MB");
        return new String(bytes, StandardCharsets.UTF_8);
      } finally {
        deadline.interrupt();
      }
    }
  }

  @Override
  public String search(String query) throws Exception {
    return fetch(search + URLEncoder.encode(query, StandardCharsets.UTF_8));
  }
}
