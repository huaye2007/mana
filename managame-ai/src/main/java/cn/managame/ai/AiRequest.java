package cn.managame.ai;

import cn.managame.Metadata;
import java.util.Objects;

/**
 * One outbound invocation passed to {@link AiTransport}.
 * {@code requestId} uniquely identifies this invocation for tracing, idempotency, and stream cancellation correlation.
 * {@code lane} names the client bulkhead lane, {@code operation} identifies the external AI workflow,
 * {@code routingKey} is an optional session/entity key (for example {@code roleId + ":" + npcId}) for sticky routing
 * and per-key concurrency control, {@code payload} is the encoded JSON request, and {@code responseSchema} is the
 * optional JSON Schema of the expected response (or stream chunk) type.
 */
public record AiRequest(String requestId, String lane, String operation, String routingKey,
                        Metadata metadata, String payload, String responseSchema) {
    public AiRequest {
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("Empty requestId");
        if (lane == null || lane.isBlank()) throw new IllegalArgumentException("Empty lane");
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("Empty operation");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(payload, "payload");
    }
}
