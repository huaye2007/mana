package cn.managame.ai.internal;

import cn.managame.ai.AiStreamEvent;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Incremental W3C Server-Sent Events parser. Decodes arbitrary byte chunks (including multi-byte UTF-8 sequences
 * split across buffers) line by line and emits one {@link AiStreamEvent} per blank-line boundary, bounding line and
 * event payload sizes to prevent unbounded heap growth on malformed streams. Not thread-safe: called sequentially
 * from one stream subscriber. Public only to be reachable across packages; not API.
 */
public final class SseParser {
    public static final int DEFAULT_MAX_EVENT_CHARS = 256 * 1024;
    private static final int LINE_HEADER_SLACK = 64;
    private static final int CHAR_BUFFER_SIZE = 1024;
    private final Consumer<AiStreamEvent> sink;
    private final int maxEventChars;
    private final int maxLineChars;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final CharBuffer chars = CharBuffer.allocate(CHAR_BUFFER_SIZE);
    private final StringBuilder line = new StringBuilder();
    private final StringBuilder data = new StringBuilder();
    private ByteBuffer pending = ByteBuffer.allocate(0);
    private boolean sawCr;
    private boolean sawBomChecked;
    private boolean hasData;
    private String eventType = "";
    private String lastEventId = "";

    public SseParser(Consumer<AiStreamEvent> sink) {
        this(sink, DEFAULT_MAX_EVENT_CHARS);
    }

    public SseParser(Consumer<AiStreamEvent> sink, int maxEventChars) {
        if (maxEventChars <= 0) throw new IllegalArgumentException("maxEventChars must be positive");
        this.sink = sink;
        this.maxEventChars = maxEventChars;
        this.maxLineChars = (int) Math.min(Integer.MAX_VALUE, (long) maxEventChars + LINE_HEADER_SLACK);
    }

    public void feed(ByteBuffer chunk) {
        if (!chunk.hasRemaining()) return;
        ByteBuffer input;
        if (pending.hasRemaining()) {
            ByteBuffer combined = ByteBuffer.allocate(pending.remaining() + chunk.remaining());
            combined.put(pending);
            combined.put(chunk);
            combined.flip();
            input = combined;
        } else {
            input = chunk;
        }
        decode(input, false);
        if (input.hasRemaining()) {
            pending = ByteBuffer.allocate(input.remaining());
            pending.put(input);
            pending.flip();
        } else {
            pending = ByteBuffer.allocate(0);
        }
    }

    public void finish() {
        decode(pending, true);
        pending = ByteBuffer.allocate(0);
        chars.clear();
        decoder.flush(chars);
        chars.flip();
        consumeChars();
        if (line.length() > 0) {
            processLine(line.toString());
            line.setLength(0);
        }
        dispatchIfReady();
    }

    private void decode(ByteBuffer input, boolean endOfInput) {
        while (true) {
            chars.clear();
            CoderResult result = decoder.decode(input, chars, endOfInput);
            chars.flip();
            consumeChars();
            if (result.isOverflow()) continue;
            break;
        }
    }

    private void consumeChars() {
        while (chars.hasRemaining()) {
            char c = chars.get();
            if (!sawBomChecked) {
                sawBomChecked = true;
                if (c == '\uFEFF') continue;
            }
            if (sawCr) {
                sawCr = false;
                if (c == '\n') continue;
            }
            if (c == '\r') {
                sawCr = true;
                processLine(line.toString());
                line.setLength(0);
            } else if (c == '\n') {
                processLine(line.toString());
                line.setLength(0);
            } else {
                if (line.length() >= maxLineChars) {
                    throw new IllegalArgumentException("SSE line exceeded max size (" + maxLineChars + " chars)");
                }
                line.append(c);
            }
        }
    }

    private void processLine(String text) {
        if (text.isEmpty()) {
            dispatchIfReady();
            return;
        }
        if (text.charAt(0) == ':') return; // SSE comment
        int colon = text.indexOf(':');
        String field;
        String value;
        if (colon < 0) {
            field = text;
            value = "";
        } else {
            field = text.substring(0, colon);
            int start = colon + 1;
            if (start < text.length() && text.charAt(start) == ' ') start++;
            value = text.substring(start);
        }
        switch (field) {
            case "data" -> {
                int added = value.length() + (hasData ? 1 : 0);
                if (data.length() + added > maxEventChars) {
                    throw new IllegalArgumentException("SSE event exceeded max size (" + maxEventChars + " chars)");
                }
                if (hasData) data.append('\n');
                data.append(value);
                hasData = true;
            }
            case "event" -> eventType = value;
            case "id" -> {
                if (value.indexOf('\0') < 0) lastEventId = value;
            }
            default -> { /* ignore retry and unknown fields */ }
        }
    }

    private void dispatchIfReady() {
        if (!hasData) {
            eventType = "";
            return;
        }
        String event = eventType.isEmpty() ? "message" : eventType;
        String payload = data.toString();
        String id = lastEventId;
        eventType = "";
        data.setLength(0);
        hasData = false;
        sink.accept(new AiStreamEvent(event, payload, id));
    }
}
