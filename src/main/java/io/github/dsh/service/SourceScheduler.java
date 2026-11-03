package io.github.dsh.service;

import io.github.dsh.runtime.HealthBoard;
import io.github.dsh.sources.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** 来源轮询各自串行、互不阻塞；processing pause 不停止 intake。 */
public final class SourceScheduler implements AutoCloseable {
  private final SourceRegistry sources;
  private final SourceContext context;
  private final HealthBoard health;
  private final Runnable received;
  private final Clock clock;
  private final java.util.concurrent.ExecutorService pool =
      Executors.newVirtualThreadPerTaskExecutor();
  private java.util.function.Function<SourceInstance, SourceContext> contexts;

  public void contextFor(java.util.function.Function<SourceInstance, SourceContext> contexts) {
    this.contexts = contexts;
  }

  private java.util.function.Predicate<SourceInstance> ready = source -> true;

  public void readyWhen(java.util.function.Predicate<SourceInstance> ready) {
    this.ready = ready;
  }

  private final Map<String, Long> due = new ConcurrentHashMap<>();
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;

  public SourceScheduler(
      SourceRegistry sources,
      SourceContext context,
      HealthBoard health,
      Runnable received,
      Clock clock) {
    this.sources = sources;
    this.context = context;
    this.health = health;
    this.received = received;
    this.clock = clock;
  }

  public void tick() {
    if (closed) return;
    for (SourceInstance source : sources.instances())
      if (source.enabled()
          && ready.test(source)
          && due.getOrDefault(source.id(), 0L) <= clock.millis()
          && running.add(source.id())) pool.submit(() -> poll(source));
  }

  private void poll(SourceInstance source) {
    try {
      HealthBoard.Entry before = health.view().get(source.id());
      SourceInstance.PollResult result =
          source.poll(contexts == null ? context : contexts.apply(source));
      if (sources.get(source.id()) != source) return;
      due.put(source.id(), clock.millis() + Math.max(1, result.nextPollSeconds()) * 1000L);
      if (result.errors().isEmpty()) {
        if (health.view().get(source.id()) == before) health.ready(source.id());
      } else health.failed(source.id(), String.join("; ", result.errors()), 3);
      if (result.received() > 0) received.run();
    } catch (Exception failure) {
      if (sources.get(source.id()) != source) return;
      due.put(source.id(), clock.millis() + 30_000);
      health.failed(source.id(), failure.getClass().getSimpleName(), 3);
    } finally {
      running.remove(source.id());
    }
  }

  public void refresh(String id) {
    due.remove(id);
  }

  @Override
  public void close() {
    closed = true;
    pool.shutdown();
    try {
      if (!pool.awaitTermination(10, TimeUnit.SECONDS)) pool.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pool.shutdownNow();
    }
  }
}
