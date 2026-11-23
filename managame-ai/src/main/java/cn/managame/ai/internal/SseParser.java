package cn.managame.ai.internal;

import cn.managame.ai.AiChunk;
import java.util.function.Consumer;

/**
 * Incremental Server-Sent Events (SSE) line parser. Internal implementation, not public API.
 */
public final class SseParser {
    private static final String DEFAULT_EVENT = "message";
    private final StringBuilder data = new StringBuilder();
    private boolean hasData;
    private String event;
    private String id;

    /** Feeds one line (without trailing CR/LF) and emits an {@link AiChunk} on each event boundary. */
    public void feedLine(String line, Consumer<AiChunk> onChunk) {
        if (line.isEmpty()) {
            flush(onChunk);
            return;
        }
        if (line.charAt(0) == ':') return; // comment
        int colon = line.indexOf(':');
        String field;
        String value;
        if (colon < 0) {
            field = line;
            value = "";
        } else {
            field = line.substring(0, colon);
            int valueStart = colon + 1;
            if (valueStart < line.length() && line.charAt(valueStart) == ' ') valueStart++;
            value = line.substring(valueStart);
        }
        switch (field) {
            case "data" -> {
                if (hasData) data.append('\n');
                data.append(value);
                hasData = true;
            }
            case "event" -> event = value;
            case "id" -> {
                if (value.indexOf('\0') < 0) id = value;
            }
            default -> {} // ignore retry and unknown fields
        }
    }

    /** Flushes any trailing buffered event at end-of-stream. */
    public void finish(Consumer<AiChunk> onChunk) {
        flush(onChunk);
    }

    private void flush(Consumer<AiChunk> onChunk) {
        if (!hasData) {
            event = null;
            return;
        }
        String eventName = (event == null || event.isEmpty()) ? DEFAULT_EVENT : event;
        String payload = data.toString();
        String eventId = id;
        data.setLength(0);
        hasData = false;
        event = null;
        onChunk.accept(new AiChunk(eventName, payload, eventId));
    }
}
