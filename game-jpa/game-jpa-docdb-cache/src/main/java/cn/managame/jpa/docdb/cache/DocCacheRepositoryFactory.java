package cn.managame.jpa.docdb.cache;

import cn.managame.jpa.cache.CacheConfig;
import cn.managame.jpa.cache.NewRolePolicy;
import cn.managame.jpa.cache.annotation.Warmup;
import cn.managame.jpa.core.bootstrap.ModelTypes;
import cn.managame.jpa.core.context.ComponentRegistry;
import cn.managame.jpa.core.converter.TypeConverterAware;
import cn.managame.jpa.core.converter.TypeConverterRegistry;
import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.lifecycle.LifecycleDispatcher;
import cn.managame.jpa.core.metadata.EntityMetadata;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.registry.DataSourceBinding;
import cn.managame.jpa.core.registry.MetadataRegistry;
import cn.managame.jpa.core.repository.RepositoryFactory;
import cn.managame.jpa.core.repository.RepositoryTypes;
import cn.managame.jpa.core.routing.RoutingStrategy;
import cn.managame.jpa.core.routing.RoutingStrategyRegistry;
import cn.managame.jpa.core.write.ShardWriteRouter;
import cn.managame.jpa.core.write.WriteChannel;
import cn.managame.jpa.core.write.WriteChannelRegistry;
import cn.managame.jpa.core.write.WriteTaskSubmitter;
import cn.managame.jpa.docdb.executor.DocExecutor;
import cn.managame.jpa.docdb.metadata.DocEntityMetadata;
import cn.managame.jpa.docdb.repository.DefaultDocRepository;
import cn.managame.jpa.docdb.repository.DocRepository;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DocDB cache repository factory.
 */
public class DocCacheRepositoryFactory implements RepositoryFactory {

    private static final Logger log = LoggerFactory.getLogger(DocCacheRepositoryFactory.class);

    private final Map<Class<?>, CacheConfig> configMap = new ConcurrentHashMap<>();
    private final Map<Class<?>, DocUniqueCacheRepository<?, ?>> warmupUniqueRepositories = new ConcurrentHashMap<>();
    private final Set<String> registeredHandlers = ConcurrentHashMap.newKeySet();
    private final Set<Class<?>> warmedEntities = ConcurrentHashMap.newKeySet();
    private CacheConfig defaultConfig = CacheConfig.defaults();

    public DocCacheRepositoryFactory() {
    }

    public DocCacheRepositoryFactory defaultConfig(CacheConfig config) {
        this.defaultConfig = config;
        return this;
    }

    public DocCacheRepositoryFactory configFor(Class<?> entityType, CacheConfig config) {
        configMap.put(entityType, config);
        return this;
    }

    @Override
    public boolean supports(Class<?> repositoryType) {
        return IDocUniqueCacheRepository.class.isAssignableFrom(repositoryType)
                || IDocMultiCacheRepository.class.isAssignableFrom(repositoryType);
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public Object createRepository(Class<?> repositoryType, ComponentRegistry registry) {
        Class<?> entityType = RepositoryTypes.resolveEntityType(repositoryType, DocRepository.class);
        DocEntityMetadata metadata = docMetadata(registry, entityType);
        RepositoryDependencies dependencies = resolveDependencies(registry, metadata);
        String writeEntityName = ensureWriteChannel(metadata, registry, dependencies);
        DefaultDocRepository<Object, Object> delegate = createDelegate(metadata, dependencies);

        if (IDocMultiCacheRepository.class.isAssignableFrom(repositoryType)) {
            DocCacheKeyMeta cacheKeyMeta = DocCacheKeyMeta.resolve(metadata);
            return new DocMultiCacheRepository<>(metadata, delegate, cacheKeyMeta, effectiveConfig(metadata),
                    dependencies.writeSubmitter(), writeEntityName,
                    effectiveNewRolePolicy(metadata, registry));
        }

        return uniqueCacheRepository(metadata, delegate, registry, dependencies, writeEntityName);
    }

    /** {@code @Warmup} 文档实体在上下文创建完成后自动 findAll 预热（对齐 RDB 侧行为）。 */
    public void warmUpAnnotatedCaches(ComponentRegistry registry) {
        MetadataRegistry metadataRegistry = registry.get(MetadataRegistry.class);
        for (EntityMetadata entityMetadata : metadataRegistry.getByModel(ModelTypes.DOCDB)) {
            if (entityMetadata instanceof DocEntityMetadata docMetadata && isWarmup(docMetadata)) {
                warmUpAnnotatedCache(docMetadata, registry);
            }
        }
    }

    private void warmUpAnnotatedCache(DocEntityMetadata metadata, ComponentRegistry registry) {
        if (warmedEntities.contains(metadata.entityType())) {
            return;
        }
        RepositoryDependencies dependencies = resolveDependencies(registry, metadata);
        if (metadata.hasShardKey() && dependencies.routingStrategy() != null) {
            throw new GameJpaException("@Warmup cannot warm up sharded DocDB entity "
                    + metadata.entityType().getName() + "; use a non-sharded collection or explicit warmUp per shard");
        }

        String writeEntityName = ensureWriteChannel(metadata, registry, dependencies);
        DefaultDocRepository<Object, Object> delegate = createDelegate(metadata, dependencies);
        DocUniqueCacheRepository<Object, Object> cacheRepo =
                uniqueCacheRepository(metadata, delegate, registry, dependencies, writeEntityName);

        if (!warmedEntities.add(metadata.entityType())) {
            return;
        }
        try {
            log.info("[GameJpa] Warming DocDB cache: {}", metadata.entityType().getName());
            cacheRepo.warmUpAll();
            log.info("[GameJpa] DocDB cache warmed: {}", metadata.entityType().getName());
        } catch (RuntimeException | Error e) {
            warmedEntities.remove(metadata.entityType());
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private DocUniqueCacheRepository<Object, Object> uniqueCacheRepository(DocEntityMetadata metadata,
                                                                           DefaultDocRepository<Object, Object> delegate,
                                                                           ComponentRegistry registry,
                                                                           RepositoryDependencies dependencies,
                                                                           String writeEntityName) {
        if (!isWarmup(metadata)) {
            return createUniqueCacheRepository(metadata, delegate, registry, dependencies, writeEntityName);
        }
        // @Warmup 实体共享同一份常驻缓存：预热与 getRepository 返回同一实例
        return (DocUniqueCacheRepository<Object, Object>) warmupUniqueRepositories.computeIfAbsent(
                metadata.entityType(),
                ignored -> createUniqueCacheRepository(metadata, delegate, registry, dependencies, writeEntityName));
    }

    private DocUniqueCacheRepository<Object, Object> createUniqueCacheRepository(DocEntityMetadata metadata,
                                                                                 DefaultDocRepository<Object, Object> delegate,
                                                                                 ComponentRegistry registry,
                                                                                 RepositoryDependencies dependencies,
                                                                                 String writeEntityName) {
        Function<Object, Object> dbLoader = delegate::findById;
        Function<Object, Object> idExtractor = entity -> metadata.idField().accessor().get(entity);
        Supplier<java.util.List<Object>> allLoader = delegate::findAll;
        return new DocUniqueCacheRepository<>(metadata, delegate, dependencies.writeSubmitter(), writeEntityName,
                dbLoader, idExtractor, allLoader, effectiveConfig(metadata),
                dependencies.routingStrategy() != null, effectiveNewRolePolicy(metadata, registry));
    }

    private CacheConfig effectiveConfig(DocEntityMetadata metadata) {
        CacheConfig configured = configMap.getOrDefault(metadata.entityType(), defaultConfig);
        if (!isWarmup(metadata)) {
            return configured;
        }
        return CacheConfig.permanent(configured.cacheStoreFactory());
    }

    private NewRolePolicy effectiveNewRolePolicy(DocEntityMetadata metadata, ComponentRegistry registry) {
        if (isWarmup(metadata)) {
            return NewRolePolicy.disabled();
        }
        NewRolePolicy policy = registry.find(NewRolePolicy.class);
        return policy != null ? policy : NewRolePolicy.disabled();
    }

    private boolean isWarmup(DocEntityMetadata metadata) {
        return metadata.entityType().isAnnotationPresent(Warmup.class);
    }

    private String ensureWriteChannel(DocEntityMetadata metadata, ComponentRegistry registry,
                                      RepositoryDependencies dependencies) {
        String writeEntityName = writeEntityName(metadata);
        if (registeredHandlers.add(writeEntityName)) {
            WriteChannelRegistry writeChannelRegistry = registry.get(WriteChannelRegistry.class);
            DocBatchExecutor batchExecutor = new DocBatchExecutor(metadata, dependencies.executor(),
                    dependencies.metrics());
            ShardWriteRouter router = new ShardWriteRouter(metadata, dependencies.routingStrategy(),
                    dependencies.homeDataSource(), "DocDB");
            writeChannelRegistry.register(new WriteChannel.Merge(writeEntityName, router, batchExecutor));
        }
        return writeEntityName;
    }

    private String writeEntityName(DocEntityMetadata metadata) {
        return ModelTypes.DOCDB.modelName() + ":" + metadata.entityType().getName();
    }

    private DocEntityMetadata docMetadata(ComponentRegistry registry, Class<?> entityType) {
        MetadataRegistry metadataRegistry = registry.get(MetadataRegistry.class);
        return metadataRegistry.get(entityType, DocEntityMetadata.class)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No Doc metadata found for entity: " + entityType.getName()));
    }

    private RepositoryDependencies resolveDependencies(ComponentRegistry registry, DocEntityMetadata metadata) {
        DocExecutor executor = registry.get(DocExecutor.class);
        configureConverters(registry, executor);
        MetricsCollector metrics = registry.get(MetricsCollector.class);
        LifecycleDispatcher lifecycle = registry.get(LifecycleDispatcher.class);
        RoutingStrategy routingStrategy = resolveRoutingStrategy(registry, metadata);
        WriteTaskSubmitter writeSubmitter = registry.get(WriteTaskSubmitter.class);
        String homeDataSource = DataSourceBinding.resolveHomeDataSource(registry, metadata);
        return new RepositoryDependencies(executor, metrics, lifecycle, routingStrategy, writeSubmitter,
                homeDataSource);
    }

    private DefaultDocRepository<Object, Object> createDelegate(DocEntityMetadata metadata,
                                                                RepositoryDependencies dependencies) {
        return new DefaultDocRepository<>(metadata, dependencies.executor(), dependencies.lifecycle(),
                dependencies.metrics(), dependencies.routingStrategy(), dependencies.homeDataSource());
    }

    private RoutingStrategy resolveRoutingStrategy(ComponentRegistry registry, DocEntityMetadata metadata) {
        RoutingStrategyRegistry routingRegistry = registry.find(RoutingStrategyRegistry.class);
        if (routingRegistry != null) {
            return routingRegistry.resolve(metadata.entityType(), metadata.logicalName());
        }
        return registry.find(RoutingStrategy.class);
    }

    private void configureConverters(ComponentRegistry registry, DocExecutor executor) {
        if (executor instanceof TypeConverterAware aware) {
            aware.setTypeConverterRegistry(registry.get(TypeConverterRegistry.class));
        }
    }

    private record RepositoryDependencies(DocExecutor executor,
                                          MetricsCollector metrics,
                                          LifecycleDispatcher lifecycle,
                                          RoutingStrategy routingStrategy,
                                          WriteTaskSubmitter writeSubmitter,
                                          String homeDataSource) {
    }
}
