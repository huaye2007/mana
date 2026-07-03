package com.github.huaye2007.mana.jpa.rdb.cache;

import com.github.huaye2007.mana.jpa.cache.CacheConfig;
import com.github.huaye2007.mana.jpa.cache.NewRolePolicy;
import com.github.huaye2007.mana.jpa.cache.annotation.Warmup;
import com.github.huaye2007.mana.jpa.cache.impl.UniqueCacheRepository;
import com.github.huaye2007.mana.jpa.core.bootstrap.ModelTypes;
import com.github.huaye2007.mana.jpa.core.context.ComponentRegistry;
import com.github.huaye2007.mana.jpa.core.converter.TypeConverterAware;
import com.github.huaye2007.mana.jpa.core.converter.TypeConverterRegistry;
import com.github.huaye2007.mana.jpa.core.exception.GameJpaException;
import com.github.huaye2007.mana.jpa.core.lifecycle.LifecycleDispatcher;
import com.github.huaye2007.mana.jpa.core.metadata.EntityMetadata;
import com.github.huaye2007.mana.jpa.core.metrics.MetricsCollector;
import com.github.huaye2007.mana.jpa.core.registry.DataSourceBinding;
import com.github.huaye2007.mana.jpa.core.registry.MetadataRegistry;
import com.github.huaye2007.mana.jpa.core.repository.RepositoryFactory;
import com.github.huaye2007.mana.jpa.core.repository.RepositoryTypes;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategy;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategyRegistry;
import com.github.huaye2007.mana.jpa.core.write.ShardWriteRouter;
import com.github.huaye2007.mana.jpa.core.write.WriteChannel;
import com.github.huaye2007.mana.jpa.core.write.WriteChannelRegistry;
import com.github.huaye2007.mana.jpa.core.write.WriteTaskSubmitter;
import com.github.huaye2007.mana.jpa.rdb.executor.RdbExecutor;
import com.github.huaye2007.mana.jpa.rdb.metadata.RdbEntityMetadata;
import com.github.huaye2007.mana.jpa.rdb.repository.DefaultRdbRepository;
import com.github.huaye2007.mana.jpa.rdb.repository.RdbRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RdbCacheRepositoryFactory implements RepositoryFactory {

    private static final Logger log = LoggerFactory.getLogger(RdbCacheRepositoryFactory.class);

    private final Map<Class<?>, CacheConfig> configMap = new ConcurrentHashMap<>();
    private final Map<Class<?>, UniqueCacheRepository<?, ?>> warmupUniqueRepositories = new ConcurrentHashMap<>();
    private final Set<String> registeredHandlers = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> warmedEntities = ConcurrentHashMap.newKeySet();
    private CacheConfig defaultConfig = CacheConfig.defaults();

    public RdbCacheRepositoryFactory() {
    }

    public RdbCacheRepositoryFactory defaultConfig(CacheConfig config) {
        this.defaultConfig = config;
        return this;
    }

    public RdbCacheRepositoryFactory configFor(Class<?> entityType, CacheConfig config) {
        configMap.put(entityType, config);
        return this;
    }

    @Override
    public boolean supports(Class<?> repositoryType) {
        return IRdbUniqueCacheRepository.class.isAssignableFrom(repositoryType)
                || IRdbMultiCacheRepository.class.isAssignableFrom(repositoryType);
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object createRepository(Class<?> repositoryType, ComponentRegistry registry) {
        Class<?> entityType = RepositoryTypes.resolveEntityType(repositoryType, RdbRepository.class);
        MetadataRegistry metadataRegistry = registry.get(MetadataRegistry.class);
        RdbEntityMetadata metadata = metadataRegistry.get(entityType, RdbEntityMetadata.class)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No RDB metadata found for entity: " + entityType.getName()));

        RepositoryDependencies dependencies = resolveDependencies(registry, metadata);
        CacheConfig config = effectiveConfig(metadata);
        NewRolePolicy newRolePolicy = effectiveNewRolePolicy(metadata, registry);
        DefaultRdbRepository delegate = createDelegate(metadata, dependencies);
        String writeEntityName = ensureWriteChannel(metadata, registry, dependencies);

        if (IRdbMultiCacheRepository.class.isAssignableFrom(repositoryType)) {
            RdbCacheKeyMeta cacheKeyMeta = RdbCacheKeyMeta.resolve(metadata);
            return new RdbMultiCacheRepository<>(registry, metadata, delegate, cacheKeyMeta, config,
                    dependencies.writeSubmitter(), writeEntityName, newRolePolicy);
        }

        UniqueCacheRepository cacheRepo = uniqueCacheRepository(metadata, delegate, config,
                dependencies.writeSubmitter(), writeEntityName, newRolePolicy);
        return new RdbUniqueCacheRepository<>(metadata, delegate, cacheRepo,
                dependencies.routingStrategy() != null);
    }

    public void warmUpAnnotatedCaches(ComponentRegistry registry) {
        MetadataRegistry metadataRegistry = registry.get(MetadataRegistry.class);
        for (EntityMetadata entityMetadata : metadataRegistry.getByModel(ModelTypes.RDB)) {
            if (entityMetadata instanceof RdbEntityMetadata rdbMetadata && isWarmup(rdbMetadata)) {
                warmUpAnnotatedCache(rdbMetadata, registry);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void warmUpAnnotatedCache(RdbEntityMetadata metadata, ComponentRegistry registry) {
        if (warmedEntities.contains(metadata.entityType())) {
            return;
        }
        RepositoryDependencies dependencies = resolveDependencies(registry, metadata);
        if (metadata.hasShardKey() && dependencies.routingStrategy() != null) {
            throw new GameJpaException("@Warmup cannot warm up sharded RDB entity "
                    + metadata.entityType().getName() + "; use a non-sharded table or explicit warmUp per shard");
        }

        CacheConfig config = effectiveConfig(metadata);
        NewRolePolicy newRolePolicy = effectiveNewRolePolicy(metadata, registry);
        DefaultRdbRepository delegate = createDelegate(metadata, dependencies);
        String writeEntityName = ensureWriteChannel(metadata, registry, dependencies);
        UniqueCacheRepository cacheRepo = uniqueCacheRepository(metadata, delegate, config,
                dependencies.writeSubmitter(), writeEntityName, newRolePolicy);

        if (!warmedEntities.add(metadata.entityType())) {
            return;
        }
        try {
            log.info("[GameJpa] Warming RDB cache: {}", metadata.entityType().getName());
            cacheRepo.warmUpAll();
            log.info("[GameJpa] RDB cache warmed: {}, size={}",
                    metadata.entityType().getName(), cacheRepo.size());
        } catch (RuntimeException | Error e) {
            warmedEntities.remove(metadata.entityType());
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private <T, ID> UniqueCacheRepository<T, ID> uniqueCacheRepository(RdbEntityMetadata metadata,
                                                                       DefaultRdbRepository<T, ID> delegate,
                                                                       CacheConfig config,
                                                                       WriteTaskSubmitter writeSubmitter,
                                                                       String writeEntityName,
                                                                       NewRolePolicy newRolePolicy) {
        if (!isWarmup(metadata)) {
            return createUniqueCacheRepository(metadata, delegate, config, writeSubmitter, writeEntityName,
                    newRolePolicy);
        }
        return (UniqueCacheRepository<T, ID>) warmupUniqueRepositories.computeIfAbsent(metadata.entityType(),
                ignored -> createUniqueCacheRepository(metadata, delegate, config, writeSubmitter, writeEntityName,
                        newRolePolicy));
    }

    @SuppressWarnings("unchecked")
    private <T, ID> UniqueCacheRepository<T, ID> createUniqueCacheRepository(RdbEntityMetadata metadata,
                                                                            DefaultRdbRepository<T, ID> delegate,
                                                                            CacheConfig config,
                                                                            WriteTaskSubmitter writeSubmitter,
                                                                            String writeEntityName,
                                                                            NewRolePolicy newRolePolicy) {
        java.util.function.Function<ID, T> dbLoader = delegate::findById;
        java.util.function.Function<T, ID> idExtractor = entity ->
                (ID) metadata.idField().accessor().get(entity);
        java.util.function.Supplier<List<T>> allLoader = delegate::findAll;
        return new UniqueCacheRepository<>(writeSubmitter, writeEntityName, dbLoader, idExtractor,
                allLoader, config, newRolePolicy);
    }

    private CacheConfig effectiveConfig(RdbEntityMetadata metadata) {
        CacheConfig configured = configMap.getOrDefault(metadata.entityType(), defaultConfig);
        if (!isWarmup(metadata)) {
            return configured;
        }
        return CacheConfig.permanent(configured.cacheStoreFactory());
    }

    private NewRolePolicy effectiveNewRolePolicy(RdbEntityMetadata metadata, ComponentRegistry registry) {
        if (isWarmup(metadata)) {
            return NewRolePolicy.disabled();
        }
        NewRolePolicy policy = registry.find(NewRolePolicy.class);
        return policy != null ? policy : NewRolePolicy.disabled();
    }

    private boolean isWarmup(RdbEntityMetadata metadata) {
        return metadata.entityType().isAnnotationPresent(Warmup.class);
    }

    private String ensureWriteChannel(RdbEntityMetadata metadata, ComponentRegistry registry,
                                      RepositoryDependencies dependencies) {
        String writeEntityName = writeEntityName(metadata);
        if (registeredHandlers.add(writeEntityName)) {
            WriteChannelRegistry writeChannelRegistry = registry.get(WriteChannelRegistry.class);
            RdbBatchExecutor batchExecutor = new RdbBatchExecutor(metadata, dependencies.executor(),
                    dependencies.metrics());
            ShardWriteRouter router = new ShardWriteRouter(metadata, dependencies.routingStrategy(),
                    dependencies.homeDataSource(), "RDB");
            writeChannelRegistry.register(new WriteChannel.Merge(writeEntityName, router, batchExecutor));
        }
        return writeEntityName;
    }

    private String writeEntityName(RdbEntityMetadata metadata) {
        return ModelTypes.RDB.modelName() + ":" + metadata.entityType().getName();
    }

    private RepositoryDependencies resolveDependencies(ComponentRegistry registry, RdbEntityMetadata metadata) {
        RdbExecutor executor = registry.get(RdbExecutor.class);
        configureConverters(registry, executor);
        MetricsCollector metrics = registry.get(MetricsCollector.class);
        LifecycleDispatcher lifecycle = registry.get(LifecycleDispatcher.class);
        RoutingStrategy routingStrategy = resolveRoutingStrategy(registry, metadata);
        WriteTaskSubmitter writeSubmitter = registry.get(WriteTaskSubmitter.class);
        String homeDataSource = DataSourceBinding.resolveHomeDataSource(registry, metadata);
        return new RepositoryDependencies(executor, metrics, lifecycle, routingStrategy, writeSubmitter,
                homeDataSource);
    }

    private <T, ID> DefaultRdbRepository<T, ID> createDelegate(RdbEntityMetadata metadata,
                                                              RepositoryDependencies dependencies) {
        return new DefaultRdbRepository<>(metadata, dependencies.executor(), dependencies.lifecycle(),
                dependencies.metrics(), dependencies.routingStrategy(), dependencies.homeDataSource());
    }

    private RoutingStrategy resolveRoutingStrategy(ComponentRegistry registry, RdbEntityMetadata metadata) {
        RoutingStrategyRegistry routingRegistry = registry.find(RoutingStrategyRegistry.class);
        if (routingRegistry != null) {
            return routingRegistry.resolve(metadata.entityType(), metadata.logicalName());
        }
        return registry.find(RoutingStrategy.class);
    }

    private void configureConverters(ComponentRegistry registry, RdbExecutor executor) {
        if (executor instanceof TypeConverterAware aware) {
            aware.setTypeConverterRegistry(registry.get(TypeConverterRegistry.class));
        }
    }

    private record RepositoryDependencies(RdbExecutor executor,
                                          MetricsCollector metrics,
                                          LifecycleDispatcher lifecycle,
                                          RoutingStrategy routingStrategy,
                                          WriteTaskSubmitter writeSubmitter,
                                          String homeDataSource) {
    }
}
