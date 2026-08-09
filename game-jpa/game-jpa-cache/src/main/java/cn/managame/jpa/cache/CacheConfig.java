package cn.managame.jpa.cache;

import cn.managame.jpa.cache.store.CacheStoreFactory;
import cn.managame.jpa.cache.store.CaffeineCacheStoreFactory;

import java.time.Duration;

public class CacheConfig {

    /** {@link Builder#maximumSize(long)} 传入本值（或任意负数）表示不做条数淘汰。 */
    public static final long UNBOUNDED = -1;

    private final long maximumSize;
    private final Duration expireAfterWrite;
    private final Duration expireAfterAccess;
    private final CacheStoreFactory cacheStoreFactory;

    private CacheConfig(Builder builder) {
        this.maximumSize = builder.maximumSize;
        this.expireAfterWrite = builder.expireAfterWrite;
        this.expireAfterAccess = builder.expireAfterAccess;
        this.cacheStoreFactory = builder.cacheStoreFactory;
    }

    public long maximumSize() { return maximumSize; }
    public Duration expireAfterWrite() { return expireAfterWrite; }
    public Duration expireAfterAccess() { return expireAfterAccess; }
    public CacheStoreFactory cacheStoreFactory() { return cacheStoreFactory; }

    /**
     * 条目写入后<b>至少</b>能留存多久：两个时间型过期里较小的那个；都没配置时返回 {@code null}，
     * 表示不按时间淘汰。
     * <p>
     * 取较小值是保守估计——任一过期条件命中都会淘汰条目。这是<b>下界</b>：默认的
     * {@code expireAfterAccess} 是滑动过期，条目每次被访问都会续期，实际存活只会更久。
     * 用于和刷盘周期、新号判定 ttl 等时间参数做一致性校验。
     */
    public Duration retention() {
        if (expireAfterWrite == null) {
            return expireAfterAccess;
        }
        if (expireAfterAccess == null) {
            return expireAfterWrite;
        }
        return expireAfterWrite.compareTo(expireAfterAccess) <= 0 ? expireAfterWrite : expireAfterAccess;
    }

    public static CacheConfig defaults() {
        return builder().build();
    }

    public static CacheConfig permanent() {
        return builder().permanent().build();
    }

    public static CacheConfig permanent(CacheStoreFactory cacheStoreFactory) {
        return builder()
                .cacheStoreFactory(cacheStoreFactory)
                .permanent()
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        /**
         * 默认<b>不</b>做条数淘汰。这是写回缓存的正确性前提：条目可能还带着没刷盘的写任务，
         * 而按条数淘汰只看访问频率、不看时间，缓存打满时可能踢掉毫秒前刚写入的条目；被踢掉的条目
         * 再次读取会回源读到比队列更旧的库值，业务在旧值上改再回写就会顶掉队列里的新状态。
         * 时间型过期没有这个问题——它远大于刷盘周期，淘汰不可能跑到刷盘前面。
         * <p>
         * 内存由 {@link #expireAfterAccess} 自然封顶（默认 10 分钟内活跃的条目，也就是在线规模），
         * 业务侧再按生命周期显式 {@code evict}（如角色登出）即可。
         */
        private long maximumSize = UNBOUNDED;
        private Duration expireAfterWrite;
        /**
         * 游戏数据按<b>最后一次访问</b>过期，而不是最后一次写入。
         * <p>
         * 在线角色的数据可能长时间只读不写（战斗、查询属性），按写入过期会把还在用的数据丢掉、
         * 下次读又要回源，纯属浪费。按访问过期让活跃数据一直驻留，角色离线不再访问后自然淘汰——
         * 内存上界正好是"最近 10 分钟活跃的实体"，也就是在线规模。
         */
        private Duration expireAfterAccess = Duration.ofMinutes(10);
        private CacheStoreFactory cacheStoreFactory = CaffeineCacheStoreFactory.INSTANCE;

        /**
         * 条数上限。{@link CacheConfig#UNBOUNDED}（或任意负数）表示不做条数淘汰，这是默认值。
         * <p>
         * <b>写回缓存慎用正数</b>：条数淘汰与异步刷盘赛跑，缓存打满时可能淘汰掉尚未落库的条目。
         * 需要限制内存优先调小 {@link #expireAfterWrite}，或在业务侧按生命周期显式 evict。
         */
        public Builder maximumSize(long maximumSize) {
            this.maximumSize = maximumSize;
            return this;
        }

        public Builder expireAfterWrite(Duration duration) {
            this.expireAfterWrite = duration;
            return this;
        }

        public Builder expireAfterAccess(Duration duration) {
            this.expireAfterAccess = duration;
            return this;
        }

        public Builder cacheStoreFactory(CacheStoreFactory cacheStoreFactory) {
            this.cacheStoreFactory = cacheStoreFactory;
            return this;
        }

        public Builder permanent() {
            this.maximumSize = UNBOUNDED;
            this.expireAfterWrite = null;
            this.expireAfterAccess = null;
            return this;
        }

        public CacheConfig build() {
            return new CacheConfig(this);
        }
    }
}
