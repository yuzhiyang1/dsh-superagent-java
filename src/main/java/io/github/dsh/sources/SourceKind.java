package io.github.dsh.sources;

import static io.github.dsh.config.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 来源种类扩展契约：配置/策略/管理字段与运行实例由同一 Java 插件拥有。 */
public interface SourceKind {
  String id();

  default String kind() {
    return id();
  }

  JsonNode title();

  default JsonNode description() {
    return object("zh", "", "en", "");
  }

  ObjectNode defaultConfig();

  default boolean defaultEnabled() {
    return true;
  }

  default boolean enabledByDefault() {
    return defaultEnabled();
  }

  /** 验证完整配置并返回独立副本；无副作用，不访问网络。 */
  ObjectNode validateConfig(JsonNode value);

  default ObjectNode defaultPolicy() {
    return object("allowed", List.of(), "bots", List.of(), "admission", object());
  }

  default ObjectNode validatePolicy(JsonNode value) {
    if (!value.isObject()) throw new IllegalArgumentException("Source policy must be an object");
    ObjectNode result = defaults(value, defaultPolicy());
    for (String group : List.of("allowed", "bots")) {
      JsonNode rows = result.path(group);
      if (!rows.isArray()) throw new IllegalArgumentException(group + " must be an array");
      Set<String> ids = new HashSet<>();
      for (JsonNode row : rows) {
        String actor = row.path("id").asText();
        if (actor.isBlank() || !row.path("id").isTextual())
          throw new IllegalArgumentException("Actor id required");
        if (!ids.add(caseInsensitiveActors() ? actor.toLowerCase(Locale.ROOT) : actor))
          throw new IllegalArgumentException("Duplicate actor id");
      }
    }
    if (!result.path("admission").isObject())
      throw new IllegalArgumentException("Admission must be an object");
    return result;
  }

  record Scope(boolean listened, boolean observed, String row, String reason) {}

  default Scope scope(io.github.dsh.core.Model.Trigger trigger, JsonNode config) {
    return new Scope(true, false, null, null);
  }

  default boolean channelAllowed(Map<String, String> address, JsonNode config) {
    return true;
  }

  default JsonNode channelLink(Map<String, String> address, JsonNode config) {
    return object("url", address.getOrDefault("url", ""), "label", title());
  }

  default List<String> refreshLabels(SourceContext context, SourceInstance source)
      throws Exception {
    return List.of();
  }

  default ObjectNode synchronizePolicy(
      SourceContext context, SourceInstance source, JsonNode current) throws Exception {
    throw new IllegalArgumentException("Source has no policy synchronization");
  }

  default boolean caseInsensitiveActors() {
    return false;
  }

  default boolean builtinValidation() {
    return false;
  }

  default JsonNode fields() {
    return array(List.of());
  }

  default JsonNode policyFields() {
    return array(List.of());
  }

  default JsonNode policySync() {
    return MAPPER.nullNode();
  }

  default boolean syncReady(JsonNode config, JsonNode policy) {
    return !policySync().isNull();
  }

  default boolean canRefreshLabels() {
    return false;
  }

  default JsonNode policyRows(JsonNode config) {
    return array(List.of());
  }

  default JsonNode explicit() {
    return object(
        "zh", "名单中的人明确向智能体发出请求。", "en", "A listed person explicitly addresses the agent.");
  }

  default JsonNode facts(JsonNode setup) {
    return array(List.of());
  }

  default JsonNode environment(JsonNode config, JsonNode setup) {
    return object("variables", List.of());
  }

  default Map<String, String> environmentValues(
      JsonNode config, JsonNode setup, io.github.dsh.core.Model.Trigger trigger) {
    Map<String, String> values = new LinkedHashMap<>();
    for (JsonNode item : environment(config, setup).path("variables"))
      if (item.path("key").isTextual())
        values.put(item.path("key").asText(), item.path("value").asText());
    return Collections.unmodifiableMap(values);
  }

  default Set<String> health() {
    return Set.of(id());
  }

  default Set<String> restartKeys() {
    return Set.of();
  }

  default Set<String> panelOnlyKeys() {
    return Set.of();
  }

  default JsonNode descriptor() {
    return descriptor(defaultConfig(), object());
  }

  default JsonNode descriptor(JsonNode config) {
    return descriptor(config, object());
  }

  default JsonNode descriptor(JsonNode config, JsonNode setup) {
    return object(
        "kind",
        id(),
        "id",
        id(),
        "title",
        title(),
        "description",
        description(),
        "fields",
        fields(),
        "health",
        health(),
        "facts",
        facts(setup),
        "environment",
        environment(config, setup),
        "policy",
        object(
            "fields",
            policyFields(),
            "rows",
            policyRows(config),
            "explicit",
            explicit(),
            "sync",
            policySync()),
        "refreshLabels",
        canRefreshLabels(),
        "restartKeys",
        restartKeys(),
        "panelOnlyKeys",
        panelOnlyKeys(),
        "enabledByDefault",
        defaultEnabled());
  }

  SourceInstance create(JsonNode instance, JsonNode fullPolicy, Map<String, String> environment);
}
