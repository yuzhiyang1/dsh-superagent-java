package io.github.dsh.sources;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsh.core.Model.OutboxMessage;
import java.util.List;

/** 来源协议端口：接收配置、游标、上下文读取和回信归来源拥有，授权由领域负责。 */
public interface SourceInstance extends AutoCloseable {
    record Find(List<String> threads,List<String> terms){public Find{threads=List.copyOf(threads);terms=List.copyOf(terms);}}
    default Find find(String query){return new Find(List.of(),List.of());}
    String id();
    String kind();
    boolean enabled();
    Object configuration();
    void configure(JsonNode configuration);
    PollResult poll(SourceContext context) throws Exception;
    Object read(SourceContext context, String resource) throws Exception;
    default Object readContext(SourceContext context, io.github.dsh.core.Model.Trigger trigger, String part, String cursor) throws Exception {
        throw new IllegalArgumentException("Unsupported source context part");
    }
    Delivery send(SourceContext context, OutboxMessage message) throws Exception;
    default void start(SourceContext context) throws Exception {}
    @Override default void close() throws Exception {}
    record PollResult(int received, int nextPollSeconds, List<String> errors) {
        public PollResult { errors = List.copyOf(errors); }
    }
    record Delivery(String status, String receipt) {
        public Delivery { if (!List.of("sent", "gone", "waiting").contains(status)) throw new IllegalArgumentException("Unknown delivery status"); }
    }
}
