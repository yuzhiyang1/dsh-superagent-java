package io.github.dsh.runtime;

import io.github.dsh.core.Model.OutboxMessage;

/** 返回外部回执。消息 id 应作为幂等键；网络送达和数据库提交之间无法做到通用 exactly-once。 */
@FunctionalInterface
public interface ChannelSender {
  String send(OutboxMessage message) throws Exception;
}
