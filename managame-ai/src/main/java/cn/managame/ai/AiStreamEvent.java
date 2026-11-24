package cn.managame.ai;

import java.util.Objects;

/**
 * One W3C Server-Sent Events (SSE) message delivered by {@link AiClient#stream}. {@code event} defaults to
 * {@code "message"} when the server sent no {@code event:} field; {@code id} is the last event id or empty.
 */
public record AiStreamEvent(String event, String data, String id) {
    public AiStreamEvent {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(id, "id");
    }
}
