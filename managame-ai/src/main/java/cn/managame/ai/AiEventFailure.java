package cn.managame.ai;

import cn.managame.Metadata;

/**
 * An event that {@link AiEventPublisher} could not deliver (queue full, publisher closed, encode error,
 * or exhausted sink retries). {@code payload} is the original Java event object or encoded JSON string.
 */
public record AiEventFailure(String eventId, String event, String partitionKey, long occurredAt,
                             Metadata metadata, Object payload, String reason, Throwable cause) {}
