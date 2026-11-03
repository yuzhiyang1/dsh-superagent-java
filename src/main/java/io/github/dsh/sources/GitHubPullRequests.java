package io.github.dsh.sources;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsh.execution.ExecutionSettings;

/** 执行宿主的真实 GitHub 发布端口；仍须 Source 写开关与宿主 allowPublish 双重授权。 */
public final class GitHubPullRequests implements ExecutionSettings.PullRequests {
  private final GitHubSource source;

  public GitHubPullRequests(GitHubSource source) {
    this.source = source;
  }

  @Override
  public ExecutionSettings.PullRequest create(
      String title, String body, String head, String base, boolean draft) throws Exception {
    return map(source.createPull(title, body, head, base, draft));
  }

  @Override
  public ExecutionSettings.PullRequest read(int number) throws Exception {
    return map(source.readPull(number));
  }

  private static ExecutionSettings.PullRequest map(JsonNode value) {
    return new ExecutionSettings.PullRequest(
        value.path("number").asInt(),
        value.path("html_url").asText(),
        value.path("head").path("ref").asText(),
        value.path("draft").asBoolean());
  }
}
