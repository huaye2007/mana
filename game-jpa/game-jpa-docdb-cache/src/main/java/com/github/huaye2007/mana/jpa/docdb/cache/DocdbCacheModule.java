package com.github.huaye2007.mana.jpa.docdb.cache;

import com.github.huaye2007.mana.jpa.cache.CacheConfig;
import com.github.huaye2007.mana.jpa.cache.NewRoleDetector;
import com.github.huaye2007.mana.jpa.cache.NewRolePolicy;
import com.github.huaye2007.mana.jpa.core.bootstrap.BootstrapHook;
import com.github.huaye2007.mana.jpa.core.bootstrap.PersistenceConfigurer;
import com.github.huaye2007.mana.jpa.core.bootstrap.PersistenceModule;
import com.github.huaye2007.mana.jpa.core.context.ComponentRegistry;
import com.github.huaye2007.mana.jpa.core.registry.MetadataRegistry;
import com.github.huaye2007.mana.jpa.docdb.executor.DocExecutor;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadataResolver;

import java.time.Duration;

public class DocdbCacheModule implements PersistenceModule {

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
