package cn.managame.jpa.docdb.cache;

import cn.managame.jpa.cache.CacheConfig;
import cn.managame.jpa.cache.NewRoleDetector;
import cn.managame.jpa.cache.NewRolePolicy;
import cn.managame.jpa.core.bootstrap.BootstrapHook;
import cn.managame.jpa.core.bootstrap.GameJpaExtension;
import cn.managame.jpa.core.bootstrap.PersistenceConfigurer;
import cn.managame.jpa.core.context.ComponentRegistry;
import cn.managame.jpa.core.registry.MetadataRegistry;
import cn.managame.jpa.docdb.executor.DocExecutor;
import cn.managame.jpa.docdb.metadata.DocEntityMetadataResolver;

import java.time.Duration;

public class DocdbCacheModule implements GameJpaExtension {

    private final DocCacheRepositoryFactory factory = new DocCacheRepositoryFactory();
    private final DocExecutor executor;
    private NewRolePolicy newRolePolicy = NewRolePolicy.disabled();

    public DocdbCacheModule() {
        this(null);
    }

    public DocdbCacheModule(DocExecutor executor) {
        this.executor = executor;
    }

    public static DocdbCacheModule withExecutor(DocExecutor executor) {
        return new DocdbCacheModule(executor);
    }

    /**
     * Enables cache-backed document repositories using the executor supplied by a
     * document database storage extension.
     */
    public static DocdbCacheModule defaults() {
        return new DocdbCacheModule();
    }

    public DocdbCacheModule defaultConfig(CacheConfig config) {
        factory.defaultConfig(config);
        return this;
    }

    public DocdbCacheModule configFor(Class<?> entityType, CacheConfig config) {
        factory.configFor(entityType, config);
        return this;
    }

    public DocdbCacheModule newRoleDetector(NewRoleDetector detector, Duration ttl) {
        this.newRolePolicy = NewRolePolicy.of(detector, ttl);
        return this;
    }

    public DocdbCacheModule newRolePolicy(NewRolePolicy newRolePolicy) {
        this.newRolePolicy = newRolePolicy != null ? newRolePolicy : NewRolePolicy.disabled();
        return this;
    }

    @Override
    public void configure(PersistenceConfigurer configurer) {
        configurer.addResolver(new DocEntityMetadataResolver());
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
            configurer.registerComponent(DocExecutor.class, executor);
        }
    }
}
