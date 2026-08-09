package cn.managame.jpa.cache;

import cn.managame.jpa.core.exception.ConfigurationException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

public final class NewRolePolicy {

    private static final NewRolePolicy DISABLED = new NewRolePolicy(null, Duration.ZERO, null);

    private final NewRoleDetector detector;
    private final Duration ttl;
    private final Function<Object, Object> roleIdExtractor;
    private final Cache<Object, Boolean> cache;

    private NewRolePolicy(NewRoleDetector detector, Duration ttl, Function<Object, Object> roleIdExtractor) {
        this.detector = detector;
        this.ttl = ttl;
        this.roleIdExtractor = roleIdExtractor;
        // 判定结果必须按「计算时刻」硬过期，不能像实体缓存那样按访问续期：
        // 同一个 roleId 反复 cacheLoad 未命中会不停命中这个判定，按访问续期的话
        // 「是新号」可以一直不过期，对已经写入的数据永久返回 null。
        this.cache = detector == null ? null : Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .build();
    }

    public static NewRolePolicy disabled() {
        return DISABLED;
    }

    public static NewRolePolicy of(NewRoleDetector detector, Duration ttl) {
        return newPolicy(detector, ttl, null);
    }

    public static NewRolePolicy of(NewRoleDetector detector, Duration ttl,
                                   Function<Object, Object> roleIdExtractor) {
        Objects.requireNonNull(roleIdExtractor, "roleIdExtractor");
        return newPolicy(detector, ttl, roleIdExtractor);
    }

    private static NewRolePolicy newPolicy(NewRoleDetector detector, Duration ttl,
                                           Function<Object, Object> roleIdExtractor) {
        Objects.requireNonNull(detector, "detector");
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        return new NewRolePolicy(detector, ttl, roleIdExtractor);
    }

    public boolean enabled() {
        return detector != null;
    }

    /** 新号判定结果的缓存时长；未启用时返回 {@code null}。 */
    public Duration ttl() {
        return detector == null ? null : ttl;
    }

    /**
     * 校验新号判定的 ttl <b>严格短于</b>实体缓存的留存时间，不满足则启动期 fail-fast。
     * <p>
     * 判定为「新号」时 {@code cacheLoad} 直接返回 {@code null}、不回源。之后业务 {@code cacheInsert}
     * 写入数据，读取靠的是实体缓存命中。如果实体缓存条目<b>先于</b>这个判定过期，下一次
     * {@code cacheLoad} 会未命中 → 复用还没过期的「是新号」判定 → 对<b>已经存在</b>的数据继续返回
     * {@code null}，数据看起来凭空消失，直到判定过期为止。
     * <p>
     * 让判定永远先过期，这个窗口就不存在。实体缓存没有配置时间型过期（如 {@code permanent()}）时
     * 条目不会先消失，无需校验。
     */
    public void verifyExpiresBefore(CacheConfig cacheConfig, Class<?> entityType) {
        if (detector == null) {
            return;
        }
        Duration retention = cacheConfig.retention();
        if (retention == null) {
            return;
        }
        if (ttl.compareTo(retention) >= 0) {
            throw new ConfigurationException("实体 " + entityType.getName()
                    + " 的新号判定 ttl (" + ttl + ") 必须短于实体缓存留存时间 (" + retention
                    + ")；否则缓存条目过期后，未过期的「是新号」判定会让 cacheLoad 对已经写入的数据返回 null。"
                    + "请调小 newRoleDetector(detector, ttl) 的 ttl，或调大该实体 CacheConfig 的过期时间。");
        }
    }

    public boolean hasRoleIdExtractor() {
        return roleIdExtractor != null;
    }

    public boolean skipLoad(Object roleId) {
        if (detector == null) {
            return false;
        }
        if (roleId == null) {
            return false;
        }

        return cache.get(roleId, detector::isNewRole);
    }

    public boolean skipLoad(Object source, Function<Object, Object> fallbackRoleIdExtractor) {
        if (detector == null) {
            return false;
        }
        Function<Object, Object> extractor = roleIdExtractor != null ? roleIdExtractor : fallbackRoleIdExtractor;
        if (extractor == null) {
            return false;
        }
        return skipLoad(extractor.apply(source));
    }
}
