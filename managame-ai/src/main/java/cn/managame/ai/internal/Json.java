package cn.managame.ai.internal;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal RFC 8259 JSON parser and writer for the framework's own envelopes (JSON-RPC 2.0, JSON Schema and the basic
 * codec). Public only to be reachable across {@code managame-ai}'s packages; not API.
 */
public final class Json {
    private static final int MAX_DEPTH = 64;

    private Json() {}

    /** Embeds already-encoded JSON verbatim when writing an object or array, such as a pre-built schema. */
    public record Raw(String json) {}

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("JSON text is null");
        Parser parser = new Parser(text);
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (!parser.eof()) throw parser.error("Unexpected trailing character '" + parser.peek() + "'");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("Expected a JSON object");
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder(128);
        writeTo(out, value, 0);
        return out.toString();
    }

    public static void writeString(StringBuilder out, CharSequence value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00");
                        out.append(hex((c >> 4) & 0xF));
                        out.append(hex(c & 0xF));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static char hex(int nibble) { return (char) (nibble < 10 ? '0' + nibble : 'a' + (nibble - 10)); }

    private static void writeTo(StringBuilder out, Object value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nesting exceeds " + MAX_DEPTH);
        switch (value) {
            case null -> out.append("null");
            case Raw raw -> out.append(raw.json());
            case String s -> writeString(out, s);
            case CharSequence cs -> writeString(out, cs);
            case Character c -> writeString(out, String.valueOf(c));
            case Boolean b -> out.append(b ? "true" : "false");
            case Integer i -> out.append(i.intValue());
            case Long l -> out.append(l.longValue());
            case Short s -> out.append(s.shortValue());
            case Byte b -> out.append(b.byteValue());
            case Double d -> {
                if (!Double.isFinite(d)) throw new IllegalArgumentException("Non-finite number: " + d);
                out.append(d.doubleValue());
            }
            case Float f -> {
                if (!Float.isFinite(f)) throw new IllegalArgumentException("Non-finite number: " + f);
                out.append(f.floatValue());
            }
            case BigInteger bi -> out.append(bi);
            case BigDecimal bd -> out.append(bd);
            case Enum<?> e -> writeString(out, e.name());
            case Optional<?> opt -> writeTo(out, opt.orElse(null), depth + 1);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() == null) throw new IllegalArgumentException("JSON object key must not be null");
                    if (!first) out.append(',');
                    first = false;
                    writeString(out, entry.getKey().toString());
                    out.append(':');
                    writeTo(out, entry.getValue(), depth + 1);
                }
                out.append('}');
            }
            case Iterable<?> items -> {
                out.append('[');
                boolean first = true;
                for (Object item : items) {
                    if (!first) out.append(',');
                    first = false;
                    writeTo(out, item, depth + 1);
                }
                out.append(']');
            }
            default -> {
                Class<?> type = value.getClass();
                if (type.isArray()) {
                    out.append('[');
                    int length = Array.getLength(value);
                    for (int i = 0; i < length; i++) {
                        if (i > 0) out.append(',');
                        writeTo(out, Array.get(value, i), depth + 1);
                    }
                    out.append(']');
                } else if (type.isRecord()) {
                    writeRecord(out, value, depth);
                } else {
                    throw new IllegalArgumentException("Cannot encode " + type.getName() + " with the basic JSON codec; configure an AiCodec");
                }
            }
        }
    }

    private static void writeRecord(StringBuilder out, Object record, int depth) {
        out.append('{');
        RecordComponent[] components = record.getClass().getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) out.append(',');
            writeString(out, components[i].getName());
            out.append(':');
            try {
                var accessor = components[i].getAccessor();
                accessor.setAccessible(true);
                writeTo(out, accessor.invoke(record), depth + 1);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Cannot read record component " + components[i].getName(), e);
            }
        }
        out.append('}');
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        boolean eof() { return pos >= s.length(); }

        char peek() { return s.charAt(pos); }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
                else break;
            }
        }

        Object readValue(int depth) {
            if (depth > MAX_DEPTH) throw error("JSON nesting exceeds " + MAX_DEPTH);
            skipWhitespace();
            if (eof()) throw error("Unexpected end of JSON");
            return switch (peek()) {
                case '{' -> readObject(depth + 1);
                case '[' -> readArray(depth + 1);
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> {
                    char c = peek();
                    if (c == '-' || (c >= '0' && c <= '9')) yield readNumber();
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        private Map<String, Object> readObject(int depth) {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!eof() && peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (eof() || peek() != '"') throw error("Expected string key");
                String key = readString();
                skipWhitespace();
                if (eof() || peek() != ':') throw error("Expected ':' after key");
                pos++;
                Object value = readValue(depth);
                map.put(key, value);
                skipWhitespace();
                if (eof()) throw error("Unterminated JSON object");
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw error("Expected ',' or '}' in object");
                }
            }
        }

        private List<Object> readArray(int depth) {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!eof() && peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue(depth));
                skipWhitespace();
                if (eof()) throw error("Unterminated JSON array");
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw error("Expected ',' or ']' in array");
                }
            }
        }

        private String readString() {
            pos++; // opening '"'
            int start = pos;
            StringBuilder out = null;
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return out == null ? s.substring(start, pos - 1) : out.toString();
                }
                if (c < 0x20) throw error("Unescaped control character in string");
                if (c == '\\') {
                    if (out == null) {
                        out = new StringBuilder(pos - start + 16);
                        out.append(s, start, pos - 1);
                    }
                    if (eof()) throw error("Unterminated escape sequence");
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> out.append(esc);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) throw error("Incomplete unicode escape");
                            int codePoint = 0;
                            for (int i = 0; i < 4; i++) {
                                int d = Character.digit(s.charAt(pos++), 16);
                                if (d < 0) throw error("Invalid hex digit in unicode escape");
                                codePoint = (codePoint << 4) | d;
                            }
                            out.append((char) codePoint);
                        }
                        default -> throw error("Invalid escape '\\" + esc + "'");
                    }
                } else if (out != null) {
                    out.append(c);
                }
            }
            throw error("Unterminated string");
        }

        private Object readLiteral(String literal, Object result) {
            if (!s.startsWith(literal, pos)) throw error("Invalid token");
            pos += literal.length();
            return result;
        }

        private Number readNumber() {
            int start = pos;
            boolean floating = false;
            if (peek() == '-') pos++;
            if (eof()) throw error("Invalid number");
            char first = peek();
            if (first == '0') {
                pos++;
            } else if (first >= '1' && first <= '9') {
                while (!eof() && isDigit(peek())) pos++;
            } else {
                throw error("Invalid number");
            }
            if (!eof() && peek() == '.') {
                floating = true;
                pos++;
                if (eof() || !isDigit(peek())) throw error("Expected digit after '.'");
                while (!eof() && isDigit(peek())) pos++;
            }
            if (!eof() && (peek() == 'e' || peek() == 'E')) {
                floating = true;
                pos++;
                if (!eof() && (peek() == '+' || peek() == '-')) pos++;
                if (eof() || !isDigit(peek())) throw error("Expected digit in exponent");
                while (!eof() && isDigit(peek())) pos++;
            }
            String token = s.substring(start, pos);
            if (!floating) {
                try {
                    return Long.parseLong(token);
                } catch (NumberFormatException overflow) {
                    return new BigInteger(token);
                }
            }
            double value = Double.parseDouble(token);
            if (!Double.isFinite(value)) throw error("Number out of range: " + token);
            return value;
        }

        private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + pos);
        }
    }
}
