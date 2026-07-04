package cn.managame.jpa.rdb.cache;

import cn.managame.jpa.cache.CacheConfig;
import cn.managame.jpa.cache.NewRoleDetector;
import cn.managame.jpa.cache.NewRolePolicy;
import cn.managame.jpa.core.bootstrap.BootstrapHook;
import cn.managame.jpa.core.bootstrap.PersistenceConfigurer;
import cn.managame.jpa.core.bootstrap.PersistenceModule;
import cn.managame.jpa.core.context.ComponentRegistry;
import cn.managame.jpa.core.registry.MetadataRegistry;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadataResolver;
import cn.managame.jpa.rdb.repository.RdbLogRepositoryFactory;

import java.time.Duration;

public class RdbCacheModule implements PersistenceModule {

    private final RdbCacheRepositoryFactory factory = new RdbCacheRepositoryFactory();
    private final RdbExecutor executor;
    private NewRolePolicy newRolePolicy = NewRolePolicy.disabled();

    public RdbCacheModule() {
        this(null);
    }

    public RdbCacheModule(RdbExecutor executor) {
        this.executor = executor;
    }

    public static RdbCacheModule withExecutor(RdbExecutor executor) {
        return new RdbCacheModule(executor);
    }

    public RdbCacheModule defaultConfig(CacheConfig config) {
        factory.defaultConfig(config);
        return this;
    }

    public RdbCacheModule configFor(Class<?> entityType, CacheConfig config) {
        factory.configFor(entityType, config);
        return this;
    }

    public RdbCacheModule newRoleDetector(NewRoleDetector detector, Duration ttl) {
        this.newRolePolicy = NewRolePolicy.of(detector, ttl);
        return this;
    }

    public RdbCacheModule newRolePolicy(NewRolePolicy newRolePolicy) {
        this.newRolePolicy = newRolePolicy != null ? newRolePolicy : NewRolePolicy.disabled();
        return this;
    }

    @Override
    public void configure(PersistenceConfigurer configurer) {
        configurer.addResolver(new RdbEntityMetadataResolver());
        configurer.addRepositoryFactory(new RdbLogRepositoryFactory());
        configurer.addRepositoryFactory(factory);
        if (newRolePolicy.enabled()) {
            configurer.registerComponent(NewRolePolicy.class, newRolePolicy);
        }
        configurer.addBootstrapHook(new BootstrapHook() {
            @Override
            public void afterMetadataScan(MetadataRegistry registry) {
            }

            @Override
            public void afterContextCreated(ComponentRegistry registry) {
                factory.warmUpAnnotatedCaches(registry);
            }
        });
        if (executor != null) {
            configurer.registerComponent(RdbExecutor.class, executor);
        }
    }
}
