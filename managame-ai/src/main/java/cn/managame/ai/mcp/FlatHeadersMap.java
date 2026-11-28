package cn.managame.ai.mcp;

import io.netty.handler.codec.http.HttpHeaders;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Compact, immutable, case-insensitive {@link Map} backed by a flat {@code String[]} array ({@code [k0, v0, k1, v1...]}),
 * avoiding {@link java.util.TreeMap} node allocations on every HTTP MCP request.
 */
final class FlatHeadersMap extends AbstractMap<String, String> {
    private static final Map<String, String> EMPTY = Map.of();

    private final String[] pairs;
    private final int size;
    private Set<Entry<String, String>> entrySetView;

    private FlatHeadersMap(String[] pairs, int size) {
        this.pairs = pairs;
        this.size = size;
    }

    static Map<String, String> of(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) return EMPTY;
        String[] buf = new String[headers.size() * 2];
        int count = 0;
        for (Map.Entry<String, String> entry : headers) {
            String key = entry.getKey();
            if (!containsKeyIgnoreCase(buf, count, key)) {
                buf[count * 2] = key;
                buf[count * 2 + 1] = entry.getValue();
                count++;
            }
        }
        if (count == 0) return EMPTY;
        return new FlatHeadersMap(count * 2 == buf.length ? buf : Arrays.copyOf(buf, count * 2), count);
    }

    static Map<String, String> of(Map<String, String> source) {
        if (source == null || source.isEmpty()) return EMPTY;
        if (source instanceof FlatHeadersMap) return source;
        String[] buf = new String[source.size() * 2];
        int count = 0;
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null) continue;
            int idx = indexOfIgnoreCase(buf, count, key);
            if (idx >= 0) {
                buf[idx + 1] = entry.getValue();
            } else {
                buf[count * 2] = key;
                buf[count * 2 + 1] = entry.getValue();
                count++;
            }
        }
        if (count == 0) return EMPTY;
        return new FlatHeadersMap(count * 2 == buf.length ? buf : Arrays.copyOf(buf, count * 2), count);
    }

    private static boolean containsKeyIgnoreCase(String[] pairs, int count, String key) {
        return indexOfIgnoreCase(pairs, count, key) >= 0;
    }

    private static int indexOfIgnoreCase(String[] pairs, int count, String key) {
        int end = count * 2;
        for (int i = 0; i < end; i += 2) {
            if (pairs[i].equalsIgnoreCase(key)) return i;
        }
        return -1;
    }

    @Override public int size() { return size; }

    @Override public boolean isEmpty() { return size == 0; }

    @Override public boolean containsKey(Object key) {
        return key instanceof String s && indexOfIgnoreCase(pairs, size, s) >= 0;
    }

    @Override public String get(Object key) {
        if (!(key instanceof String s)) return null;
        int idx = indexOfIgnoreCase(pairs, size, s);
        return idx >= 0 ? pairs[idx + 1] : null;
    }

    @Override public Set<Entry<String, String>> entrySet() {
        Set<Entry<String, String>> view = entrySetView;
        if (view == null) {
            view = new AbstractSet<>() {
                @Override public int size() { return size; }
                @Override public Iterator<Entry<String, String>> iterator() {
                    return new Iterator<>() {
                        private int cursor;
                        @Override public boolean hasNext() { return cursor < size * 2; }
                        @Override public Entry<String, String> next() {
                            if (!hasNext()) throw new NoSuchElementException();
                            String k = pairs[cursor];
                            String v = pairs[cursor + 1];
                            cursor += 2;
                            return new SimpleImmutableEntry<>(k, v);
                        }
                    };
                }
            };
            entrySetView = view;
        }
        return view;
    }
}
