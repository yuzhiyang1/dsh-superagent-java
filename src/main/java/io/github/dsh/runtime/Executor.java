package io.github.dsh.runtime;

import io.github.dsh.core.Model.*;
import java.util.*;

/** 执行器只能消费领域层已授权的 Assignment；所有领域工具由 Context 回到 Engine。 */
@FunctionalInterface
public interface Executor extends AutoCloseable {
  Report execute(Ticket ticket, Assignment assignment) throws Exception;

  default Report execute(
      Ticket ticket, Assignment assignment, java.util.function.BooleanSupplier stillAuthorized)
      throws Exception {
    if (!stillAuthorized.getAsBoolean()) return new Report(ReportKind.DROPPED, "执行授权已终止");
    return execute(ticket, assignment);
  }

  /**
   * Checked once after preparation, immediately before a new turn accepts its inputs. Returns the
   * current ticket snapshot so inputs arriving during preparation are delivered in that turn.
   */
  @FunctionalInterface
  interface BeforeTurn {
    Ticket check() throws Exception;
  }

  default Report execute(
      Ticket ticket,
      Assignment assignment,
      java.util.function.BooleanSupplier stillAuthorized,
      BeforeTurn beforeTurn)
      throws Exception {
    Ticket current = beforeTurn.check();
    return execute(current, assignment, stillAuthorized);
  }

  default void setContext(Context context) {}

  /** 对运行中的会话投递，不等模型结束。true 表示宿主已持久化；false 交给普通 execute。 */
  default boolean deliver(Ticket ticket, Assignment assignment, List<Input> inputs)
      throws Exception {
    return false;
  }

  default void stop(String ticketId, String assignmentId) throws Exception {}

  default BackgroundState backgroundState(String ticketId) {
    return new BackgroundState(false, false, 0, 0);
  }

  default List<Event> pollEvents() {
    return List.of();
  }

  default void acknowledgeEvent(String eventId) throws Exception {}

  default Set<String> consumedInputIds(String ticketId) {
    return Set.of();
  }

  default boolean tracksInputHistory() {
    return false;
  }

  default boolean requestCompaction(String ticketId) throws Exception {
    return false;
  }

  default Map<String, Object> sessionView(String ticketId) throws Exception {
    return Map.of();
  }

  @Override
  default void close() throws Exception {}

  record BackgroundState(
      boolean turnRunning, boolean holdsSlot, int runningJobs, int runningSubagents) {}

  enum EventKind {
    HISTORY_ACCEPTED,
    TURN_ENDED,
    WAKE,
    REPORT,
    FAILURE
  }

  record Event(
      String id,
      EventKind kind,
      String ticketId,
      String assignmentId,
      List<String> inputIds,
      Report report,
      String detail) {
    public Event {
      inputIds = inputIds == null ? List.of() : List.copyOf(inputIds);
    }
  }

  interface Context {
    /** 执行时再做领域授权与参数检查，不能信任模型报告的身份或 scope。 */
    Object invoke(String tool, String ticketId, String assignmentId, Map<String, Object> arguments)
        throws Exception;

    default Ticket currentTicket(String ticketId) {
      return null;
    }
  }
}
