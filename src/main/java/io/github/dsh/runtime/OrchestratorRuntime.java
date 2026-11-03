package io.github.dsh.runtime;

import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntSupplier;

/** 持久副作用调度器。领域事务只入队；网络、模型和 prepare 在虚拟线程运行。 每工单最多一个执行回合，普通槽位之外保留一个人工续接槽，人工输入先于新 launch。 */
public final class OrchestratorRuntime implements AutoCloseable {
  @FunctionalInterface
  public interface MainHandler {
    void handle(Effect effect) throws Exception;
  }

  public static class Later extends Exception {
    public Later(String reason) {
      super(reason);
    }
  }

  public static class ChannelGone extends Exception {
    public ChannelGone(String reason) {
      super(reason);
    }
  }

  private final Engine engine;
  private final SqliteStore store;
  private final Executor executor;
  private final ChannelSender channel;
  private final Clock clock;
  private final java.util.concurrent.ExecutorService workers =
      Executors.newVirtualThreadPerTaskExecutor();
  private final Map<String, Running> running = new ConcurrentHashMap<>();
  private final Set<String> activeTurns = ConcurrentHashMap.newKeySet();
  private final Set<String> slots = ConcurrentHashMap.newKeySet();
  private final HealthBoard health;
  private volatile IntSupplier maxWorkers = () -> 4;
  private volatile MainHandler main;
  private volatile boolean closing, automaticReplies = true;
  private final Object dispatchLock = new Object();
  private final java.util.concurrent.atomic.AtomicLong completedAttempts =
      new java.util.concurrent.atomic.AtomicLong();

  private record Running(Effect effect, CompletableFuture<Void> future) {}

  public OrchestratorRuntime(
      Engine engine, SqliteStore store, Executor executor, ChannelSender channel, Clock clock) {
    this.engine = engine;
    this.store = store;
    this.executor = executor;
    this.channel = channel;
    this.clock = clock;
    health = new HealthBoard(clock);
  }

  public void setMaxWorkers(IntSupplier limit) {
    maxWorkers = Objects.requireNonNull(limit);
  }

  public void setMainHandler(MainHandler handler) {
    main = handler;
    automaticReplies = false;
  }

  public HealthBoard health() {
    return health;
  }

  public Map<String, Object> load() {
    return Map.of(
        "running",
        slots.size(),
        "queued",
        engine.pendingAssignments().size(),
        "maxWorkers",
        maxWorkers.getAsInt());
  }

  public Executor executor() {
    return executor;
  }

  /** 周期 pass 不等待 worker；慢模型/prepare 不挡其他工单或人工管理。 */
  public int pump() {
    synchronized (dispatchLock) {
      if (closing) return 0;
      consumeExecutorEvents();
      for (EffectKind kind : EffectKind.values()) refreshEffectHealth(kind);
      for (var entry : running.values())
        store.renewEffect(entry.effect.id(), entry.effect.generation(), clock.millis() + 300_000);
      slots.removeIf(id -> !ticketRunning(id) && !executor.backgroundState(id).holdsSlot());
      Set<EffectKind> kinds =
          engine.processing().paused() ? Set.of(EffectKind.STOP) : EnumSet.allOf(EffectKind.class);
      List<Effect> due = new ArrayList<>(store.claimEffects(clock.millis(), 100, kinds, 300_000));
      due.sort(Comparator.comparingInt(this::priority));
      int launched = 0;
      for (Effect effect : due) {
        if (running.containsKey(effect.id())) continue;
        if (!canRun(effect)) {
          release(effect);
          continue;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        running.put(effect.id(), new Running(effect, future));
        workers.submit(
            () -> {
              try {
                attempt(effect);
              } finally {
                synchronized (dispatchLock) {
                  running.remove(effect.id());
                  completedAttempts.incrementAndGet();
                  future.complete(null);
                }
              }
            });
        launched++;
      }
      return launched;
    }
  }

  /** CLI/测试等待当前可运行工作结束；未来退避项保留，不忙等、不跳过真实时间。 */
  public int drain(int maximum) {
    if (maximum < 1) throw new IllegalArgumentException("maximum must be positive");
    int count = 0;
    while (count < maximum) {
      long completedBefore = completedAttempts.get();
      int started = pump();
      count += started;
      List<CompletableFuture<Void>> active =
          running.values().stream().map(Running::future).toList();
      if (active.isEmpty()) {
        // Worker 可能恰好在 pump 与快照之间结束并释放槽，不能把这个竞态当成 idle。
        if (started > 0 || completedAttempts.get() != completedBefore) continue;
        break;
      }
      CompletableFuture.allOf(active.toArray(CompletableFuture[]::new)).join();
    }
    return count;
  }

  private int priority(Effect effect) {
    return switch (effect.kind()) {
      case STOP -> 0;
      case INPUT -> humanWake(effect) ? 1 : 4;
      case RELAY, NOTICE, ASSESSMENT -> 2;
      case REPLY -> 3;
      case START -> 5;
    };
  }

  private boolean humanWake(Effect effect) {
    // 新工单即使由人发起也是普通 launch；保留槽只给已有会话的人工续接/状态请求。
    if (effect.kind() != EffectKind.INPUT) return false;
    Ticket ticket = engine.ticket(effect.payload().get("ticketId"));
    if (ticket == null) return false;
    return ticket.inputs.stream()
        .anyMatch(
            i ->
                !i.acknowledged
                    && !i.held
                    && i.readAt == null
                    && (i.kind == InputKind.TRIGGER && i.human && !i.contextOnly
                        || i.kind == InputKind.TEXT));
  }

  private boolean ticketRunning(String ticketId) {
    return running.values().stream()
        .anyMatch(
            r ->
                Objects.equals(ticketId, r.effect.payload().get("ticketId"))
                    && Set.of(EffectKind.START, EffectKind.INPUT).contains(r.effect.kind()));
  }

  private boolean canRun(Effect effect) {
    if (Set.of(EffectKind.ASSESSMENT, EffectKind.RELAY, EffectKind.NOTICE)
        .contains(effect.kind())) {
      if (main == null) return false;
      return running.values().stream()
              .noneMatch(
                  r ->
                      Set.of(EffectKind.ASSESSMENT, EffectKind.RELAY, EffectKind.NOTICE)
                          .contains(r.effect.kind()))
          && firstOrdered(effect);
    }
    if (effect.kind() == EffectKind.REPLY) return firstReply(effect);
    if (effect.kind() == EffectKind.STOP) return true;
    String ticketId = effect.payload().get("ticketId");
    Ticket ticket = engine.ticket(ticketId);
    if (ticket == null || ticket.status == TicketStatus.ENDED) return true;
    // 即使 STOP 正在退避，也必须等旧 Assignment 的清理完成，不能并行启动替代工单。
    if (store.effects().stream()
        .anyMatch(
            e ->
                e.kind() == EffectKind.STOP
                    && Objects.equals(ticketId, e.payload().get("ticketId")))) return false;
    if (ticketRunning(ticketId)) return effect.kind() == EffectKind.INPUT; // deliver 到正在运行的会话，不另占槽。
    if (engine.executionHeld(ticketId) && !statusInput(effect, ticket)) return false;
    int limit = Math.max(1, maxWorkers.getAsInt()) + (humanWake(effect) ? 1 : 0);
    if (!slots.contains(ticketId) && slots.size() >= limit) return false;
    slots.add(ticketId);
    return true;
  }

  private boolean statusInput(Effect effect, Ticket ticket) {
    if (effect.kind() != EffectKind.INPUT) return false;
    String id = effect.payload().get("inputId");
    return ticket.inputs.stream()
        .anyMatch(i -> Objects.equals(i.id, id) && i.kind == InputKind.TEXT && !i.acknowledged);
  }

  private boolean firstOrdered(Effect effect) {
    return store.effects().stream()
        .filter(e -> e.kind() == effect.kind())
        .findFirst()
        .map(e -> e.id().equals(effect.id()))
        .orElse(true);
  }

  private boolean firstReply(Effect effect) {
    OutboxMessage message = engine.outboxMessage(effect.payload().get("messageId"));
    if (message == null) return true;
    for (Effect before : store.effects()) {
      if (before.id().equals(effect.id())) return true;
      if (before.kind() != EffectKind.REPLY || before.attempts() >= 3) continue;
      OutboxMessage other = engine.outboxMessage(before.payload().get("messageId"));
      if (other != null
          && "pending".equals(other.status())
          && Objects.equals(message.ticketId(), other.ticketId())
          && message.channel().source().equals(other.channel().source())
          && message.channel().thread().equals(other.channel().thread())) return false;
    }
    return true;
  }

  private void release(Effect effect) {
    store.renewEffect(effect.id(), effect.generation(), clock.millis());
  }

  private void attempt(Effect effect) {
    try {
      if (engine.processing().paused() && effect.kind() != EffectKind.STOP)
        throw new Later("paused");
      switch (effect.kind()) {
        case REPLY -> send(effect);
        case STOP -> {
          executor.stop(effect.payload().get("ticketId"), effect.payload().get("assignmentId"));
          slots.remove(effect.payload().get("ticketId"));
        }
        case START, INPUT -> execute(effect);
        case ASSESSMENT, RELAY, NOTICE -> main.handle(effect);
      }
      store.completeEffect(effect.id(), effect.generation());
    } catch (Later waiting) {
      store.deferEffect(
          effect.id(),
          effect.generation(),
          clock.millis() + (effect.kind() == EffectKind.REPLY ? 5000 : 2000));
    } catch (Exception failure) {
      if (closing) {
        release(effect);
        return;
      } // 停机取消不是任务失败；保留 intent 给重启恢复。
      String error = failure.getClass().getSimpleName();
      long initial = effect.kind() == EffectKind.REPLY ? 5000 : 2000,
          max = effect.kind() == EffectKind.REPLY ? 300000 : 60000;
      if (effect.kind() == EffectKind.START
          && (!transientFailure(failure) || effect.attempts() >= 3)) {
        failLaunch(effect, error);
        store.completeEffect(effect.id(), effect.generation());
      } else {
        store.failEffect(effect.id(), effect.generation(), error, clock.millis(), initial, max);
        if (effect.kind() == EffectKind.INPUT) {
          try {
            engine.deliveryFailed(
                effect.payload().get("ticketId"), effect.payload().get("assignmentId"), error);
          } catch (RuntimeException ignored) {
          }
        }
      }
    } finally {
      refreshEffectHealth(effect.kind());
      String ticketId = effect.payload().get("ticketId");
      if (ticketId != null && !executor.backgroundState(ticketId).holdsSlot())
        slots.remove(ticketId);
    }
  }

  private void refreshEffectHealth(EffectKind kind) {
    synchronized (dispatchLock) {
      List<Effect> failures =
          store.effects().stream()
              .filter(e -> e.kind() == kind && e.attempts() > 0)
              .filter(
                  e -> {
                    if (kind != EffectKind.REPLY) return true;
                    OutboxMessage message = engine.outboxMessage(e.payload().get("messageId"));
                    return message != null && "pending".equals(message.status());
                  })
              .toList();
      String part = kind.name().toLowerCase(Locale.ROOT);
      if (failures.isEmpty()) {
        health.ready(part);
        return;
      }
      Effect worst = failures.stream().max(Comparator.comparingInt(Effect::attempts)).orElseThrow();
      health.reportFailures(
          part,
          worst.lastError(),
          worst.attempts(),
          kind == EffectKind.REPLY ? 1 : kind == EffectKind.INPUT ? Integer.MAX_VALUE : 3);
    }
  }

  private static boolean transientFailure(Throwable error) {
    if (error instanceof io.github.dsh.adapters.HttpJsonTransport.ApiException api)
      return api.retryable();
    return error instanceof java.net.ConnectException
        || error instanceof java.net.SocketException
        || error instanceof java.net.http.HttpTimeoutException
        || error instanceof java.net.SocketTimeoutException;
  }

  private void failLaunch(Effect effect, String error) {
    Ticket ticket = engine.ticket(effect.payload().get("ticketId"));
    if (ticket != null
        && ticket.status == TicketStatus.OPEN
        && ticket.latestAssignment() != null
        && ticket.latestAssignment().id.equals(effect.payload().get("assignmentId")))
      engine.report(
          ticket.id,
          ticket.latestAssignment().id,
          new Report(ReportKind.FAILED, "Executor launch failed: " + error));
  }

  private void send(Effect effect) throws Exception {
    String id = effect.payload().get("messageId");
    OutboxMessage message = engine.outboxMessage(id);
    if (message == null || !"pending".equals(message.status())) return;
    try {
      engine.markReplySent(id, channel.send(message));
    } catch (ChannelGone gone) {
      engine.markReplyDropped(id, gone.getMessage());
    }
  }

  private void execute(Effect effect) throws Exception {
    String ticketId = effect.payload().get("ticketId");
    Ticket ticket = engine.ticket(ticketId);
    if (ticket == null || ticket.status == TicketStatus.ENDED || ticket.stopped) return;
    Assignment assignment = ticket.latestAssignment();
    if (assignment == null || assignment.status == AssignmentStatus.ENDED) return;
    String expected = effect.payload().get("assignmentId");
    if (expected != null && !expected.equals(assignment.id)) return;
    List<Input> inputs =
        ticket.inputs.stream().filter(i -> !i.acknowledged && i.readAt == null && !i.held).toList();
    if (effect.kind() == EffectKind.INPUT) {
      Input input =
          ticket.inputs.stream()
              .filter(i -> i.id.equals(effect.payload().get("inputId")))
              .findFirst()
              .orElse(null);
      if (input != null && (input.acknowledged || input.readAt != null)) return;
      if (executor.backgroundState(ticketId).turnRunning()) {
        if (executor.deliver(ticket, assignment, inputs)) {
          engine.inputsDelivered(ticketId, assignment.id, inputs.stream().map(i -> i.id).toList());
          return;
        }
        throw new Later("ticket busy");
      }
    }
    if (engine.executionHeld(ticketId) && !statusInput(effect, ticket))
      throw new Later("executor held");
    if (assignment.status == AssignmentStatus.PENDING)
      engine.assignmentStarted(ticketId, assignment.id);
    engine.inputsDelivered(ticketId, assignment.id, inputs.stream().map(i -> i.id).toList());
    if (!activeTurns.add(ticketId)) throw new Later("ticket turn already active");
    Report report;
    try {
      report = executor.execute(ticket, assignment, () -> authorized(ticketId, assignment.id));
    } finally {
      activeTurns.remove(ticketId);
    }
    if (!authorized(ticketId, assignment.id)) return;
    store.atomic(
        () -> {
          if (executor.tracksInputHistory())
            engine.inputsRead(
                ticketId, assignment.id, new ArrayList<>(executor.consumedInputIds(ticketId)));
          else for (Input input : inputs) engine.acknowledgeInput(ticketId, input.id);
          engine.report(ticketId, assignment.id, report);
          if (automaticReplies) replyFallback(ticket, assignment, report);
          return null;
        });
  }

  private boolean authorized(String ticketId, String assignmentId) {
    Ticket latest = engine.ticket(ticketId);
    return latest != null
        && latest.status == TicketStatus.OPEN
        && !latest.stopped
        && latest.latestAssignment() != null
        && latest.latestAssignment().id.equals(assignmentId)
        && latest.latestAssignment().status != AssignmentStatus.ENDED;
  }

  private void replyFallback(Ticket ticket, Assignment assignment, Report report) {
    String text = report.question() != null ? report.question() : report.summary();
    if (report.question() == null
        && !Set.of(ReportKind.READY, ReportKind.BLOCKED, ReportKind.FAILED).contains(report.kind()))
      return;
    String suffix =
        report.question() == null
            ? "result"
            : UUID.nameUUIDFromBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    for (int i = 0; i < ticket.channels.size(); i++)
      engine.queueReply(
          assignment.id + ":" + suffix + ":" + i, ticket.id, ticket.channels.get(i), text);
  }

  private void consumeExecutorEvents() {
    for (Executor.Event event : executor.pollEvents()) {
      try {
        if (authorized(event.ticketId(), event.assignmentId())) {
          switch (event.kind()) {
            case HISTORY_ACCEPTED ->
                engine.inputsRead(event.ticketId(), event.assignmentId(), event.inputIds());
            case TURN_ENDED -> engine.turnEnded(event.ticketId(), event.assignmentId());
            case REPORT -> engine.report(event.ticketId(), event.assignmentId(), event.report());
            case FAILURE ->
                engine.report(
                    event.ticketId(),
                    event.assignmentId(),
                    new Report(ReportKind.FAILED, event.detail()));
            case WAKE -> {
              Map<String, String> payload = new LinkedHashMap<>();
              payload.put("ticketId", event.ticketId());
              payload.put("assignmentId", event.assignmentId());
              if (!event.inputIds().isEmpty()) payload.put("inputId", event.inputIds().getFirst());
              store.enqueue(EffectKind.INPUT, "wake:" + event.id(), payload, clock.millis());
            }
          }
        }
        executor.acknowledgeEvent(event.id());
      } catch (Exception failure) {
        health.error("worker-events", failure.getClass().getSimpleName());
        break;
      }
    }
  }

  @Override
  public void close() {
    closing = true;
    try {
      executor.close();
    } catch (Exception error) {
      health.error("worker", error.getClass().getSimpleName());
    }
    workers.shutdown();
    try {
      if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      workers.shutdownNow();
    }
  }
}
