package io.github.dsh.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsh.sources.*;
import java.util.*;

/** 保存配置包含环境变量名；真正令牌仅在进程内交给 HTTP 边界。 */
public final class SourceFactory {
  private SourceFactory() {}

  public static SourceInstance create(String id, JsonNode value, ServiceOptions options) {
    return create(
        id, value, options, io.github.dsh.adapters.HttpJsonTransport.JSON.createObjectNode());
  }

  public static SourceInstance create(
      String id, JsonNode value, ServiceOptions options, JsonNode policy) {
    return create(id, value, options, policy, SourceKinds.builtins());
  }

  public static SourceInstance create(
      String id, JsonNode value, ServiceOptions options, JsonNode policy, SourceKinds kinds) {
    SourceKind kind = kinds.get(id);
    if (kind == null) throw new IllegalArgumentException("Unknown source kind: " + id);
    return kind.create(value, policy, options.environment());
  }
}
