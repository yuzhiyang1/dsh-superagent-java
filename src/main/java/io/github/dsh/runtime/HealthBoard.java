package io.github.dsh.runtime;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 每个组件只更新自己的健康项；瞬时错误先计数，三次连续失败才要求操作人关注。 */
public final class HealthBoard {
  public record Entry(String state, String detail, String at, int failures) {}

  private final Clock clock;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  public HealthBoard(Clock clock) {
    this.clock = clock;
  }

  public void remove(String part) {
    entries.remove(part);
  }

  public void starting(String part) {
    set(part, "starting", "Starting", 0);
  }

  public void ready(String part) {
    set(part, "ready", "Ready", 0);
  }

  public void paused(String part) {
    set(part, "paused", "Processing paused", 0);
  }

  public void failed(String part, String detail, int threshold) {
    Entry previous = entries.get(part);
    int failures = previous == null ? 1 : previous.failures() + 1;
    set(part, failures >= threshold ? "error" : "starting", detail, failures);
  }

  /** 队列健康以 SQLite 中仍待处理的失败为准，不被别的工单成功掩盖。 */
  public void reportFailures(String part, String detail, int failures, int threshold) {
    set(part, failures >= threshold ? "error" : "starting", detail, failures);
  }

  public void error(String part, String detail) {
    set(part, "error", detail, 1);
  }

  private void set(String part, String state, String detail, int failures) {
    entries.put(part, new Entry(state, detail, clock.instant().toString(), failures));
  }

  public Map<String, Entry> view() {
    return Collections.unmodifiableMap(new TreeMap<>(entries));
  }
}
