package cn.managame.ai;

import java.util.List;

/**
 * Application binding to its event streaming or analytics pipeline (such as Kafka, RocketMQ, NATS, or an HTTP
 * batch ingest endpoint). Invoked on bounded on-demand virtual threads with batches collected by size or linger time.
 */
@FunctionalInterface
public interface AiEventSink {
    /** Deliver an ordered batch of events. Throw on delivery failure. */
    void send(List<AiEvent> batch) throws Exception;
}
