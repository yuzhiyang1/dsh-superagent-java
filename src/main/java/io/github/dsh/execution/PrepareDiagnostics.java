package io.github.dsh.execution;

import java.util.Comparator;
import java.util.Map;
import java.util.regex.Pattern;

/** Best-effort credential filtering for preparation failures, not a general secret detector. */
final class PrepareDiagnostics {
  private static final String REDACTED = "[REDACTED]";
  private static final int LIMIT = 4096;
  private static final String TRUNCATED = "\n[diagnostic truncated]";
  private static final Pattern ANSI =
      Pattern.compile(
          "\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\|$)|\u001b\\[[0-?]*[ -/]*[@-~]");
  private static final Pattern CONTROLS = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]");
  private static final Pattern PEM =
      Pattern.compile("(?s)-----BEGIN ([A-Z0-9 ]*PRIVATE KEY)-----.*?(?:-----END \\1-----|\\z)");
  private static final Pattern AUTH =
      Pattern.compile("(?im)((?:proxy-)?authorization[\\\"']?\\s*[:=]\\s*)[^\\r\\n]*");
  private static final Pattern ASSIGNMENT =
      Pattern.compile(
          "(?i)(?<![\\w.-])([\\\"']?[\\w.-]*(?:password|passwd|secret|token|credential|api[_-]?key|private[_-]?key|access[_-]?key|cookie)[\\w.-]*[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}&]+)");
  private static final Pattern URL_USERINFO =
      Pattern.compile("(?i)(?<![a-z0-9+.-])([a-z][a-z0-9+.-]*://)[^\\s/@]+@");
  private static final Pattern TOKEN =
      Pattern.compile(
          "\\b(?:gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|sk-[A-Za-z0-9_-]{12,})\\b");

  private PrepareDiagnostics() {}

  static String sanitize(String message, Map<String, String> environment) {
    String result = clean(message == null ? "Preparation failed" : message);
    if (environment != null) {
      var secrets =
          environment.entrySet().stream()
              .filter(e -> sensitive(e.getKey()) && e.getValue() != null)
              .map(e -> clean(e.getValue()))
              .filter(v -> !v.isEmpty())
              .distinct()
              .sorted(Comparator.comparingInt(String::length).reversed())
              .toList();
      for (String secret : secrets) result = result.replace(secret, REDACTED);
    }
    result = PEM.matcher(result).replaceAll(REDACTED);
    result = AUTH.matcher(result).replaceAll("$1" + REDACTED);
    result = ASSIGNMENT.matcher(result).replaceAll("$1" + REDACTED);
    result = URL_USERINFO.matcher(result).replaceAll("$1" + REDACTED + "@");
    result = TOKEN.matcher(result).replaceAll(REDACTED);
    if (result.length() <= LIMIT) return result;
    int end = LIMIT - TRUNCATED.length();
    if (Character.isHighSurrogate(result.charAt(end - 1))) end--;
    return result.substring(0, end) + TRUNCATED;
  }

  private static boolean sensitive(String key) {
    return key != null
        && (ExecutionSettings.secret(key)
            || key.toUpperCase(java.util.Locale.ROOT).matches(".*(AUTH|PASSWD|COOKIE).*"));
  }

  private static String clean(String text) {
    return CONTROLS.matcher(ANSI.matcher(text).replaceAll("").replace("\r\n", "\n")).replaceAll("");
  }
}
