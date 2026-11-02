package io.github.dsh.runtime;

import io.github.dsh.core.Model.*;

/** 执行器只执行已获授权的工单；它不能扩大 Mandate、创建 Ticket 或改变 Policy。 */
@FunctionalInterface
public interface Executor {
    Report execute(Ticket ticket, Assignment assignment) throws Exception;
    /** 长执行器应在每次有副作用的工具前检查最新领域状态，而不是只检查开始时的快照。 */
    default Report execute(Ticket ticket, Assignment assignment, java.util.function.BooleanSupplier stillAuthorized) throws Exception {
        if (!stillAuthorized.getAsBoolean()) return new Report(ReportKind.DROPPED, "执行授权已终止");
        return execute(ticket, assignment);
    }
    default void stop(String ticketId, String assignmentId) throws Exception {}
}
