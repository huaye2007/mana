package cn.managame.ai;

import cn.managame.Metadata;
import java.util.Objects;

/**
 * Validated event envelope emitted by {@link AiEventPublisher} to downstream messaging or analytics pipelines.
 * {@code eventId} supports idempotent deduplication, {@code partitionKey} preserves per-entity ordering
 * (for example by role or guild id, or {@code null} when unpartitioned), and {@code occurredAt} records the
 * epoch milliseconds when the event was published.
 */
public record AiEvent(String eventId, String event, String partitionKey, long occurredAt,
                      Metadata metadata, String payload) {
    public AiEvent {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("Empty eventId");
        if (event == null || event.isBlank()) throw new IllegalArgumentException("Empty event name");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(payload, "payload");
    }
}
