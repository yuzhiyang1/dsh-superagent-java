package io.github.dsh.runtime;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 合并并发唤醒：一个 pass 运行时的任意多个 request 只增加一个后续 pass。 */
public final class PassCoordinator implements AutoCloseable {
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("orchestrator-pass").factory());
  private final AtomicBoolean requested = new AtomicBoolean();
  private final Runnable pass;
  private final HealthBoard health;
  private volatile boolean closed;

  public PassCoordinator(Runnable pass, HealthBoard health) {
    this.pass = pass;
    this.health = health;
  }

  public void start() {
    timer.scheduleWithFixedDelay(this::request, 0, 2, TimeUnit.SECONDS);
  }

  public void request() {
    if (!closed && requested.compareAndSet(false, true)) timer.execute(this::run);
  }

  private void run() {
    if (closed) return;
    requested.set(false);
    try {
      pass.run();
      health.ready("runtime");
    } catch (Exception error) {
      health.error("runtime", error.getClass().getSimpleName() + ": " + safe(error.getMessage()));
    }
  }

  private static String safe(String text) {
    return text == null ? "" : text.substring(0, Math.min(text.length(), 300));
  }

  @Override
  public void close() {
    closed = true;
    timer.shutdown();
    try {
      if (!timer.awaitTermination(10, TimeUnit.SECONDS)) timer.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      timer.shutdownNow();
    }
  }
}
