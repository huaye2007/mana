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
 * split across buffers) line by line without intermediate per-line {@link String} allocations, and emits one
 * {@link AiStreamEvent} per blank-line boundary while bounding line and event payload sizes. Per the W3C SSE
 * specification, incomplete trailing data without a terminating blank line at end-of-stream is discarded. Not
 * thread-safe: called sequentially from one stream subscriber. Public only to be reachable across packages; not API.
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
    private final ByteBuffer remainder = ByteBuffer.allocate(4).limit(0);
    private final StringBuilder line = new StringBuilder(128);
    private final StringBuilder data = new StringBuilder(128);
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
        if (remainder.hasRemaining()) {
            while (chunk.hasRemaining() && remainder.hasRemaining()) {
                remainder.compact();
                remainder.put(chunk.get());
                remainder.flip();
                decode(remainder, false);
            }
            if (remainder.hasRemaining()) return;
        }
        if (chunk.hasRemaining()) {
            decode(chunk, false);
            if (chunk.hasRemaining()) {
                remainder.clear();
                remainder.put(chunk);
                remainder.flip();
            }
        }
    }

    public void finish() {
        decode(remainder, true);
        remainder.limit(0);
        chars.clear();
        decoder.flush(chars);
        chars.flip();
        consumeChars();
        // Per W3C SSE spec: incomplete trailing lines or events not terminated by a blank line at EOF are discarded.
        line.setLength(0);
        data.setLength(0);
        hasData = false;
        eventType = "";
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
                processLine();
                line.setLength(0);
            } else if (c == '\n') {
                processLine();
                line.setLength(0);
            } else {
                if (line.length() >= maxLineChars) {
                    throw new IllegalArgumentException("SSE line exceeded max size (" + maxLineChars + " chars)");
                }
                line.append(c);
            }
        }
    }

    private void processLine() {
        int len = line.length();
        if (len == 0) {
            dispatchIfReady();
            return;
        }
        if (line.charAt(0) == ':') return; // SSE comment
        int colon = -1;
        for (int i = 0; i < len; i++) {
            if (line.charAt(i) == ':') {
                colon = i;
                break;
            }
        }
        int fieldLen;
        int valStart;
        if (colon < 0) {
            fieldLen = len;
            valStart = len;
        } else {
            fieldLen = colon;
            valStart = colon + 1;
            if (valStart < len && line.charAt(valStart) == ' ') valStart++;
        }
        int valLen = len - valStart;
        if (fieldLen == 4 && line.charAt(0) == 'd' && line.charAt(1) == 'a'
                && line.charAt(2) == 't' && line.charAt(3) == 'a') {
            int added = valLen + (hasData ? 1 : 0);
            if (added > maxEventChars - data.length()) {
                throw new IllegalArgumentException("SSE event exceeded max size (" + maxEventChars + " chars)");
            }
            if (hasData) data.append('\n');
            if (valLen > 0) data.append(line, valStart, len);
            hasData = true;
        } else if (fieldLen == 5 && line.charAt(0) == 'e' && line.charAt(1) == 'v'
                && line.charAt(2) == 'e' && line.charAt(3) == 'n' && line.charAt(4) == 't') {
            if (valLen == 0 || regionEquals(line, valStart, len, "message")) {
                eventType = "";
            } else {
                eventType = line.substring(valStart, len);
            }
        } else if (fieldLen == 2 && line.charAt(0) == 'i' && line.charAt(1) == 'd') {
            for (int i = valStart; i < len; i++) {
                if (line.charAt(i) == '\0') return;
            }
            if (valLen == 0) {
                lastEventId = "";
            } else if (!regionEquals(line, valStart, len, lastEventId)) {
                lastEventId = line.substring(valStart, len);
            }
        }
    }

    private static boolean regionEquals(StringBuilder sb, int start, int end, String target) {
        int len = end - start;
        if (len != target.length()) return false;
        for (int i = 0; i < len; i++) {
            if (sb.charAt(start + i) != target.charAt(i)) return false;
        }
        return true;
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
