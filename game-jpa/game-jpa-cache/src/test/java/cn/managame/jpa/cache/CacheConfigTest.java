package cn.managame.jpa.cache;

import cn.managame.jpa.cache.store.CacheStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CacheConfigTest {

    @Test
    public void writeBackCacheDoesNotEvictBySizeByDefault() {
        // 条数淘汰只看访问频率、不看时间，会和异步刷盘赛跑：缓存打满时可能踢掉毫秒前刚写入、
        // 写任务还在队列里的条目，再次读取就会回源读到更旧的库值。默认必须关掉它，
        // 内存交给远大于刷盘周期的时间型过期封顶。
        CacheConfig defaults = CacheConfig.defaults();

        assertTrue(defaults.maximumSize() < 0, "默认不做条数淘汰");
        assertNotNull(defaults.retention(), "内存由时间型过期封顶");
    }

    @Test
    public void gameDataExpiresAfterAccessNotAfterWrite() {
        // 在线角色的数据常常长时间只读不写（战斗、查属性）。按写入过期会把还在用的数据丢掉、
        // 下次读又回源；按访问过期让活跃数据驻留，离线后自然淘汰。
        CacheConfig defaults = CacheConfig.defaults();

        assertEquals(Duration.ofMinutes(10), defaults.expireAfterAccess());
        assertNull(defaults.expireAfterWrite(), "不叠加写入过期，否则热数据每 10 分钟被强制丢弃一次");
        assertEquals(Duration.ofMinutes(10), defaults.retention());
    }

    @Test
    public void unboundedSizeBuildsAWorkingStore() {
        // maximumSize 为负是「不限条数」的哨兵，CacheStore 必须能正确处理而不是抛异常。
        CacheStore<Long, String> store = CacheConfig.defaults().cacheStoreFactory()
                .create(CacheConfig.defaults());

        store.put(1L, "a");

        assertEquals("a", store.getIfPresent(1L));
    }

    @Test
    public void permanentDisablesEverySizeAndTimeEviction() {
        CacheConfig permanent = CacheConfig.permanent();

        assertEquals(CacheConfig.UNBOUNDED, permanent.maximumSize());
        assertNull(permanent.expireAfterWrite());
        assertNull(permanent.expireAfterAccess());
    }
}
