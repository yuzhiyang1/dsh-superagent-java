package io.github.dsh.management;

import static io.github.dsh.config.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 管理层到运行时的窄端口。真正的 Source、模型、进程配置由启动入口注入。 默认只读状态不宣称已经配置，未连接操作明确拒绝。 */
public interface ManagementHost {
  default int activeCount() {
    return -1;
  }

  default String masterId() {
    return "orchestrator";
  }

  default JsonNode setup() {
    return object(
        "problem",
        object(
            "place",
            "deployment",
            "code",
            "runtimeNotConnected",
            "values",
            Map.of(),
            "detail",
            "Runtime has not connected its setup service"),
        "feishuProblem",
        null,
        "accounts",
        null);
  }

  default JsonNode health() {
    return object("runtime", object("state", "starting", "detail", "Runtime not connected"));
  }

  default JsonNode models() {
    return object("default", null, "groups", List.of(), "failures", List.of());
  }

  default JsonNode sourceDetails(String id) {
    return object("installed", true);
  }

  default List<Map<String, String>> environment(String ticketId) {
    return List.of();
  }

  default void validateSettings(String block, JsonNode candidate) {}

  default boolean settingsChanged(String block, JsonNode value) {
    return false;
  }

  default void promptsChanged(int revision) {}

  default JsonNode synchronizePolicy(String source) {
    throw new ApiException(400, "No membership synchronization configured for " + source);
  }

  default List<String> refreshLabels(String source) {
    throw new ApiException(400, "No label refresh configured for " + source);
  }

  default void checkSetup() {
    throw new ApiException(400, "There is no setup check connected");
  }

  default void requestPass() {}

  default JsonNode transcript(String ticketId) {
    return array(List.of());
  }

  default JsonNode readSource(String resource, String ticketId) {
    return readSource(resource);
  }

  default JsonNode readSource(String resource) {
    throw new ApiException(404, "Source resource unavailable: " + resource);
  }

  default JsonNode findSource(String query) {
    return object("threads", List.of(), "terms", List.of());
  }

  default JsonNode sourceLinks() {
    return object();
  }

  default JsonNode channelLink(io.github.dsh.core.Model.Channel channel) {
    return object("url", "", "label", object("zh", channel.source(), "en", channel.source()));
  }

  default JsonNode sessions() {
    return object("sessions", List.of(), "notificationPending", false);
  }

  default void authorizeSourceRead(String resource, String ticketId) {
    if (ticketId != null)
      throw new ApiException(403, "Worker source resource requires host authorization");
  }
}
