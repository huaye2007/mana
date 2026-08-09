package cn.managame.jpa.cache;

import cn.managame.jpa.core.exception.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 新号判定 ttl 与实体缓存留存时间的约束。
 * <p>
 * 判定为「新号」时 cacheLoad 直接返回 null 不回源；之后业务写入的数据靠实体缓存命中读到。
 * 一旦实体缓存条目先于判定过期，cacheLoad 就会对已存在的数据继续返回 null。
 * 因此判定必须永远先过期。
 */
public class NewRolePolicyTest {

    private static final NewRoleDetector ALWAYS_NEW = roleId -> true;

    @Test
    public void rejectsTtlNotShorterThanCacheRetention() {
        // 默认实体缓存 expireAfterWrite = 10min；ttl 取到相等就已经不安全。
        NewRolePolicy sameAsCache = NewRolePolicy.of(ALWAYS_NEW, Duration.ofMinutes(10));
        NewRolePolicy longerThanCache = NewRolePolicy.of(ALWAYS_NEW, Duration.ofMinutes(30));

        assertAll(
                () -> assertThrows(ConfigurationException.class,
                        () -> sameAsCache.verifyExpiresBefore(CacheConfig.defaults(), Item.class)),
                () -> assertThrows(ConfigurationException.class,
                        () -> longerThanCache.verifyExpiresBefore(CacheConfig.defaults(), Item.class)));
    }

    @Test
    public void acceptsTtlShorterThanCacheRetention() {
        NewRolePolicy policy = NewRolePolicy.of(ALWAYS_NEW, Duration.ofMinutes(5));

        policy.verifyExpiresBefore(CacheConfig.defaults(), Item.class);

        assertEquals(Duration.ofMinutes(5), policy.ttl());
    }

    @Test
    public void retentionUsesTheShorterOfBothExpiries() {
        // 任一过期条件命中都会淘汰条目，所以留存时间取小的那个。
        CacheConfig config = CacheConfig.builder()
                .expireAfterWrite(Duration.ofMinutes(10))
                .expireAfterAccess(Duration.ofMinutes(2))
                .build();
        NewRolePolicy policy = NewRolePolicy.of(ALWAYS_NEW, Duration.ofMinutes(5));

        assertEquals(Duration.ofMinutes(2), config.retention());
        assertThrows(ConfigurationException.class,
                () -> policy.verifyExpiresBefore(config, Item.class));
    }

    @Test
    public void permanentCacheNeedsNoCheckBecauseEntriesNeverExpireFirst() {
        NewRolePolicy policy = NewRolePolicy.of(ALWAYS_NEW, Duration.ofHours(1));

        assertNull(CacheConfig.permanent().retention());
        policy.verifyExpiresBefore(CacheConfig.permanent(), Item.class);
    }

    @Test
    public void disabledPolicyIsAlwaysValid() {
        NewRolePolicy disabled = NewRolePolicy.disabled();

        disabled.verifyExpiresBefore(CacheConfig.defaults(), Item.class);

        assertNull(disabled.ttl());
        assertFalse(disabled.skipLoad(1L));
    }

    private static final class Item {
    }
}
