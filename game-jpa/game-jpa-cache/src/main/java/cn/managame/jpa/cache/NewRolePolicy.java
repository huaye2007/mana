package cn.managame.jpa.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

public final class NewRolePolicy {

    private static final NewRolePolicy DISABLED = new NewRolePolicy(null, Duration.ZERO, null);

    private final NewRoleDetector detector;
    private final Function<Object, Object> roleIdExtractor;
    private final Cache<Object, Boolean> cache;

    private NewRolePolicy(NewRoleDetector detector, Duration ttl, Function<Object, Object> roleIdExtractor) {
        this.detector = detector;
        this.roleIdExtractor = roleIdExtractor;
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
