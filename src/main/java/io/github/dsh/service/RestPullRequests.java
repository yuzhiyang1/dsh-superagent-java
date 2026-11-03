package io.github.dsh.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsh.adapters.HttpJsonTransport;
import io.github.dsh.execution.ExecutionSettings;
import java.net.URI;
import java.util.*;

/** 发布仅由显式 allowPublish 工具路径调用，draft/head/base 都实际传给 GitHub。 */
public final class RestPullRequests implements ExecutionSettings.PullRequests {
  private final HttpJsonTransport http;
  private final String repository;

  public RestPullRequests(JsonNode config, ServiceOptions options) {
    repository = config.path("repository").asText();
    if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
      throw new IllegalArgumentException("Invalid repository");
    http =
        new HttpJsonTransport(
            URI.create(config.path("apiBase").asText("https://api.github.com/")),
            options.secret(config.path("tokenEnvironment").asText("GITHUB_TOKEN")));
  }

  @Override
  public ExecutionSettings.PullRequest create(
      String title, String body, String head, String base, boolean draft) throws Exception {
    JsonNode existing =
        http.request(
            "GET",
            "repos/"
                + repository
                + "/pulls?state=open&head="
                + java.net.URLEncoder.encode(
                    repository.split("/")[0] + ":" + head, java.nio.charset.StandardCharsets.UTF_8),
            null,
            headers());
    for (JsonNode item : existing)
      if (item.path("head").path("ref").asText().equals(head)) return decode(item);
    return decode(
        http.request(
            "POST",
            "repos/" + repository + "/pulls",
            Map.of("title", title, "body", body, "head", head, "base", base, "draft", draft),
            headers()));
  }

  @Override
  public ExecutionSettings.PullRequest read(int number) throws Exception {
    if (number < 1) throw new IllegalArgumentException("Positive pull number required");
    return decode(http.request("GET", "repos/" + repository + "/pulls/" + number, null, headers()));
  }

  private static Map<String, String> headers() {
    return Map.of("Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28");
  }

  private static ExecutionSettings.PullRequest decode(JsonNode item) {
    if (!item.path("number").canConvertToInt())
      throw new IllegalArgumentException("Malformed GitHub pull response");
    return new ExecutionSettings.PullRequest(
        item.path("number").asInt(),
        item.path("html_url").asText(),
        item.path("head").path("ref").asText(),
        item.path("draft").asBoolean());
  }
}
