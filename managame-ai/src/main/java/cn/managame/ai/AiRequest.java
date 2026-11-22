package cn.managame.ai;

import cn.managame.Metadata;
import java.util.Objects;

/**
 * One outbound invocation passed to {@link AiTransport}.
 * {@code lane} names the client bulkhead lane, {@code operation} identifies the external AI workflow,
 * {@code payload} is the encoded JSON request, and {@code responseSchema} is the optional JSON Schema of the
 * expected response (or stream chunk) type so external AI services can enforce structured outputs.
 */
public record AiRequest(String lane, String operation, Metadata metadata, String payload, String responseSchema) {
    public AiRequest {
        if (lane == null || lane.isBlank()) throw new IllegalArgumentException("Empty lane");
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("Empty operation");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(payload, "payload");
    }
}
