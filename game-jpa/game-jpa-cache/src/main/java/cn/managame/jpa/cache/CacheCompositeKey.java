package cn.managame.jpa.cache;

import java.util.Arrays;

/**
 * 组合缓存键。
 * 由 @CacheKey 标注的字段值按 order 排列组成，作为 Caffeine 缓存的 key。
 */
public final class CacheCompositeKey {

    private final Object[] parts;
    private final int hashCode;

    private CacheCompositeKey(Object[] parts) {
        this.parts = parts;
        this.hashCode = Arrays.hashCode(parts);
    }

    public static CacheCompositeKey of(Object... parts) {
        return new CacheCompositeKey(parts.clone());
    }

    public Object part(int index) {
        return parts[index];
    }

    public int size() {
        return parts.length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CacheCompositeKey that)) return false;
        return Arrays.equals(parts, that.parts);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "CacheCompositeKey" + Arrays.toString(parts);
    }
}
