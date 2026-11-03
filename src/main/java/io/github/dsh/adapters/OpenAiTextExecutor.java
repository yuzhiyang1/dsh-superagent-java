package io.github.dsh.adapters;

import io.github.dsh.core.Model.*;
import io.github.dsh.runtime.Executor;
import java.util.List;

/** 将可替换模型客户端接到真正 Assignment 生命周期；产出文本报告，不伪装执行过代码。 */
public final class OpenAiTextExecutor implements Executor {
  private final OpenAiExecutor client;

  public OpenAiTextExecutor(OpenAiExecutor client) {
    this.client = client;
  }

  @Override
  public Report execute(Ticket ticket, Assignment assignment) throws Exception {
    if (ticket.status != TicketStatus.OPEN || ticket.stopped)
      throw new IllegalStateException("Ticket no longer authorized for execution");
    if (!ticket.id.equals(assignment.ticketId))
      throw new IllegalArgumentException("Assignment belongs to another Ticket");
    StringBuilder context = new StringBuilder("工单：").append(ticket.title).append("\n");
    for (Input input : ticket.inputs)
      context.append(input.contextOnly ? "[仅上下文] " : "[工单输入] ").append(input.text).append("\n");
    String text =
        client.complete(
            ticket.mandate.scope(),
            List.of(new OpenAiExecutor.Message("user", context.toString())));
    return new Report(ReportKind.READY, "文本执行器输出（未执行命令或修改文件）：\n" + text);
  }
}
