package cn.managame.jpa.cache;

import cn.managame.jpa.cache.store.CacheStoreFactory;
import cn.managame.jpa.cache.store.CaffeineCacheStoreFactory;

import java.time.Duration;

public class CacheConfig {

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
        private long maximumSize = 10_000;
        private Duration expireAfterWrite = Duration.ofMinutes(10);
        private Duration expireAfterAccess;
        private CacheStoreFactory cacheStoreFactory = CaffeineCacheStoreFactory.INSTANCE;

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
            this.maximumSize = -1;
            this.expireAfterWrite = null;
            this.expireAfterAccess = null;
            return this;
        }

        public CacheConfig build() {
            return new CacheConfig(this);
        }
    }
}
