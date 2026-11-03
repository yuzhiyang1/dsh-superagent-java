package io.github.dsh.management;

import static io.github.dsh.config.Json.object;

import com.fasterxml.jackson.databind.JsonNode;

/** 缺失插件的双语占位文字。实际表单完全来自 SourceKind 描述符。 */
final class SourceForms {
  private SourceForms() {}

  static JsonNode label(String zh, String en) {
    return object("zh", zh, "en", en);
  }
}
