package io.github.dsh.management;

import java.util.Map;

/** 可预期的管理错误，HTTP 和模型工具共用，绝不伪装成成功。 */
public final class ApiException extends RuntimeException {
  public final int status;
  public final Map<String, Object> details;

  public ApiException(int status, String message) {
    this(status, message, Map.of());
  }

  public ApiException(int status, String message, Map<String, Object> details) {
    super(message);
    this.status = status;
    this.details = details;
  }
}
