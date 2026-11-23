package cn.managame.ai.internal;

import cn.managame.ai.AiJsonCodec;
import java.lang.reflect.Type;

/**
 * Default zero-dependency {@link AiJsonCodec} for {@code String} and scalar values, plus a minimal JSON string
 * escaper for HTTP response envelopes. Internal implementation, not public API.
 */
public final class BasicJsonCodec implements AiJsonCodec {
    public static final BasicJsonCodec INSTANCE = new BasicJsonCodec();

    private BasicJsonCodec() {}

    @Override public String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean b) return b ? "true" : "false";
        if (value instanceof Number n) {
            if (n instanceof Double d && !Double.isFinite(d)) throw new IllegalArgumentException("Non-finite double: " + d);
            if (n instanceof Float f && !Float.isFinite(f)) throw new IllegalArgumentException("Non-finite float: " + f);
            return n.toString();
        }
        if (value instanceof CharSequence cs) {
            StringBuilder sb = new StringBuilder(cs.length() + 2);
            writeQuoted(sb, cs.toString());
            return sb.toString();
        }
        throw new UnsupportedOperationException(
                "Basic AiJsonCodec cannot encode " + value.getClass().getName() + "; configure a JSON codec (such as Jackson)");
    }

    @Override public Object decode(String json, Type type) {
        if (type == Void.class || type == void.class) return null;
        if (type == String.class) return json;
        String trimmed = json == null ? "" : json.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed)) return null;
        if (type == Boolean.class || type == boolean.class) {
            if ("true".equals(trimmed)) return Boolean.TRUE;
            if ("false".equals(trimmed)) return Boolean.FALSE;
            throw new IllegalArgumentException("Invalid boolean: " + trimmed);
        }
        if (type == Integer.class || type == int.class) return Integer.valueOf(trimmed);
        if (type == Long.class || type == long.class) return Long.valueOf(trimmed);
        if (type == Double.class || type == double.class) return Double.valueOf(trimmed);
        if (type == Float.class || type == float.class) return Float.valueOf(trimmed);
        if (type == Short.class || type == short.class) return Short.valueOf(trimmed);
        if (type == Byte.class || type == byte.class) return Byte.valueOf(trimmed);
        throw new UnsupportedOperationException(
                "Basic AiJsonCodec cannot decode " + type.getTypeName() + "; configure a JSON codec (such as Jackson)");
    }

    /** Appends {@code s} as a quoted, RFC 8259 escaped JSON string literal. */
    public static void writeQuoted(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
                        out.append(hex(c >> 4));
                        out.append(hex(c & 0xF));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static char hex(int v) { return (char) (v < 10 ? '0' + v : 'a' + (v - 10)); }
}
