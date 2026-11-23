package cn.managame.ai;

/**
 * One event received from a streaming {@link AiClient#stream} call (for example one Server-Sent Event frame).
 *
 * @param event SSE {@code event:} field, defaulting to {@code "message"} when omitted
 * @param data  SSE {@code data:} payload (multi-line payloads joined with {@code '\n'})
 * @param id    SSE {@code id:} field, or {@code null} when omitted
 */
public record AiChunk(String event, String data, String id) {}
