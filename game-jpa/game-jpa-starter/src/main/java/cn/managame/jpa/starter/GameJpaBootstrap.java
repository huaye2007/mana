package cn.managame.jpa.starter;

import cn.managame.jpa.async.AsyncWriteQueue;
import cn.managame.jpa.async.FlushScheduler;
import cn.managame.jpa.async.FlushThreadMode;
import cn.managame.jpa.core.bootstrap.BootstrapHook;
import cn.managame.jpa.core.bootstrap.PersistenceConfigurer;
import cn.managame.jpa.core.bootstrap.PersistenceModule;
import cn.managame.jpa.core.converter.TypeConverterRegistry;
import cn.managame.jpa.core.exception.ConfigurationException;
import cn.managame.jpa.core.lifecycle.LifecycleDispatcher;
import cn.managame.jpa.core.lifecycle.LifecycleListener;
import cn.managame.jpa.core.metadata.EntityMetadata;
import cn.managame.jpa.core.metadata.EntityMetadataResolver;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.registry.DataSourceBinding;
import cn.managame.jpa.core.registry.DataSourceCatalog;
import cn.managame.jpa.core.registry.MetadataRegistry;
import cn.managame.jpa.core.repository.GameRepository;
import cn.managame.jpa.core.repository.RepositoryFactory;
import cn.managame.jpa.core.routing.RoutingStrategy;
import cn.managame.jpa.core.routing.RoutingStrategyRegistry;
import cn.managame.jpa.core.write.WriteTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bootstrap entry point for assembling game-jpa runtime components.
 *
 * <p>The built-in async write path is intentionally in-memory: it coalesces
 * entity changes, applies backpressure, and lets {@link FlushScheduler} batch
 * writes to the configured backend. Local file persistence was removed because
 * it is hard to operate correctly in game-server shutdown and retry paths.
 */
public class GameJpaBootstrap implements PersistenceConfigurer {

    private final MetadataRegistry metadataRegistry = new MetadataRegistry();
    private final TypeConverterRegistry converterRegistry = new TypeConverterRegistry();
    private final LifecycleDispatcher lifecycleDispatcher = new LifecycleDispatcher();
    private final List<EntityMetadataResolver<?>> resolvers = new ArrayList<>();
    private final List<RepositoryFactory> repositoryFactories = new ArrayList<>();
    private final List<BootstrapHook> bootstrapHooks = new ArrayList<>();
    private final Map<Class<?>, Object> extraComponents = new LinkedHashMap<>();
    private MetricsCollector metricsCollector = MetricsCollector.NOOP;
    private final RoutingStrategyRegistry routingStrategyRegistry = new RoutingStrategyRegistry();
    private final DataSourceBinding dataSourceBinding = new DataSourceBinding();
    private long flushIntervalMillis = 5000;
    private int maxRetries = 3;
    private FlushThreadMode flushThreadMode = FlushThreadMode.VIRTUAL;
    private int flushThreadCount = Math.max(2, Runtime.getRuntime().availableProcessors());
    private int flushMaxConcurrency = 0;
    private int maxFlushBatchSize = 500;
    private int maxPendingWriteTasks = 1_000_000;
    private Consumer<WriteTask> permanentFailureHandler;

    @Override
    public GameJpaBootstrap addResolver(EntityMetadataResolver<?> resolver) {
        resolvers.add(resolver);
        return this;
    }

    @Override
    public GameJpaBootstrap addRepositoryFactory(RepositoryFactory factory) {
        repositoryFactories.add(factory);
        return this;
    }

    public GameJpaBootstrap install(PersistenceModule module) {
        module.configure(this);
        return this;
    }

    @Override
    public GameJpaBootstrap addLifecycleListener(LifecycleListener listener) {
        lifecycleDispatcher.addListener(listener);
        return this;
    }

    @Override
    public GameJpaBootstrap addBootstrapHook(BootstrapHook hook) {
        bootstrapHooks.add(Objects.requireNonNull(hook, "hook"));
        return this;
    }

    @Override
    public GameJpaBootstrap metricsCollector(MetricsCollector collector) {
        this.metricsCollector = collector != null ? collector : MetricsCollector.NOOP;
        return this;
    }

    @Override
    public GameJpaBootstrap routingStrategy(RoutingStrategy strategy) {
        this.routingStrategyRegistry.defaultStrategy(strategy);
        return this;
    }

    public GameJpaBootstrap routingStrategy(Class<?> entityType, RoutingStrategy strategy) {
        this.routingStrategyRegistry.registerEntity(entityType, strategy);
        return this;
    }

    public GameJpaBootstrap routingStrategy(String logicalName, RoutingStrategy strategy) {
        this.routingStrategyRegistry.registerLogicalName(logicalName, strategy);
        return this;
    }

    public GameJpaBootstrap flushIntervalMillis(long millis) {
        if (millis <= 0) {
            throw new IllegalArgumentException("flushIntervalMillis must be positive");
        }
        this.flushIntervalMillis = millis;
        return this;
    }

    public GameJpaBootstrap maxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        this.maxRetries = maxRetries;
        return this;
    }

    public GameJpaBootstrap flushThreadCount(int threadCount) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("flushThreadCount must be positive");
        }
        this.flushThreadCount = threadCount;
        return this;
    }

    /**
     * 刷盘 worker 线程模型，默认 {@link FlushThreadMode#VIRTUAL}（虚拟线程）。
     * 选 {@link FlushThreadMode#PLATFORM} 时 {@link #flushThreadCount(int)} 决定有界平台池大小。
     */
    public GameJpaBootstrap flushThreadMode(FlushThreadMode mode) {
        this.flushThreadMode = mode != null ? mode : FlushThreadMode.VIRTUAL;
        return this;
    }

    /**
     * 单轮并发执行的刷盘单元上限，{@code <= 0}（默认）表示不限。VIRTUAL 模式下建议设为略大于数据库连接池大小，
     * 防止分库分表时一次 fan-out 出过多 vthread 全部争抢连接、撑爆连接池或触发刷盘超时。
     */
    public GameJpaBootstrap flushMaxConcurrency(int maxConcurrency) {
        this.flushMaxConcurrency = Math.max(0, maxConcurrency);
        return this;
    }

    public GameJpaBootstrap maxFlushBatchSize(int maxBatchSize) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxFlushBatchSize must be positive");
        }
        this.maxFlushBatchSize = maxBatchSize;
        return this;
    }

    public GameJpaBootstrap maxPendingWriteTasks(int maxPendingWriteTasks) {
        if (maxPendingWriteTasks <= 0) {
            throw new IllegalArgumentException("maxPendingWriteTasks must be positive");
        }
        this.maxPendingWriteTasks = maxPendingWriteTasks;
        return this;
    }

    public GameJpaBootstrap permanentFailureHandler(Consumer<WriteTask> handler) {
        this.permanentFailureHandler = handler;
        return this;
    }

    @Override
    public <T> GameJpaBootstrap registerComponent(Class<T> type, T component) {
        extraComponents.put(type, component);
        return this;
    }

    /**
     * 把实体类绑定到指定 home 数据源（"住在哪个库"）。优先级低于实体注解 {@code @Table/@Document(dataSource=...)}。
     */
    public GameJpaBootstrap dataSource(Class<?> entityType, String name) {
        dataSourceBinding.register(entityType, name);
        return this;
    }

    /**
     * 把某个包前缀下的所有实体绑定到指定 home 数据源。命中多个前缀时取最长前缀；低于注解、低于按类绑定。
     * 适合"按包区分游戏库与日志库"，如 {@code dataSource("cn.managame.log", "log")}。
     */
    public GameJpaBootstrap dataSource(String packagePrefix, String name) {
        dataSourceBinding.registerPackage(packagePrefix, name);
        return this;
    }

    public GameJpaContext bootstrap(List<Class<?>> entityClasses) {
        EntityScanner scanner = new EntityScanner(metadataRegistry, resolvers);
        scanner.scan(entityClasses);
        for (BootstrapHook hook : bootstrapHooks) {
            hook.afterMetadataScan(metadataRegistry);
        }

        AsyncWriteQueue writeQueue = new AsyncWriteQueue(maxPendingWriteTasks, metricsCollector);
        FlushScheduler flushScheduler = new FlushScheduler(writeQueue, flushIntervalMillis, maxRetries,
                flushThreadMode, flushThreadCount, metricsCollector, maxFlushBatchSize,
                FlushScheduler.DEFAULT_BATCH_TIMEOUT_MILLIS, flushMaxConcurrency);
        if (permanentFailureHandler != null) {
            flushScheduler.onFailure(permanentFailureHandler);
        }

        GameJpaContext context = new GameJpaContext(
                metadataRegistry,
                converterRegistry,
                lifecycleDispatcher,
                writeQueue,
                flushScheduler,
                repositoryFactories,
                metricsCollector,
                routingStrategyRegistry);
        context.register(DataSourceBinding.class, dataSourceBinding);

        extraComponents.forEach((type, component) -> {
            @SuppressWarnings("unchecked")
            Class<Object> t = (Class<Object>) type;
            context.register(t, component);
            if (component instanceof java.io.Closeable closeable) {
                context.addManagedResource(closeable);
            }
        });

        try {
            validateHomeDataSources(context);
            for (BootstrapHook hook : bootstrapHooks) {
                hook.afterContextCreated(context);
            }
        } catch (RuntimeException | Error e) {
            context.close();
            throw e;
        }
        return context;
    }

    /**
     * 启动期校验：每个实体解析出的 home 数据源名都必须已在某个执行器（{@link DataSourceCatalog}）注册，
     * 否则 fail-fast。把"路由/绑定产出的库名未注册"从运行期静默丢弃提前到布局阶段暴露。
     * 执行器未暴露 catalog（如内存执行器）时跳过。
     */
    private void validateHomeDataSources(GameJpaContext context) {
        List<DataSourceCatalog> catalogs = context.findAllAssignable(DataSourceCatalog.class);
        if (catalogs.isEmpty()) {
            return;
        }
        java.util.Set<String> registered = new java.util.HashSet<>();
        for (DataSourceCatalog catalog : catalogs) {
            registered.addAll(catalog.dataSourceNames());
        }
        for (EntityMetadata metadata : metadataRegistry.getAll()) {
            String home = dataSourceBinding.resolve(metadata);
            if (!registered.contains(home)) {
                throw new ConfigurationException("实体 " + metadata.entityType().getName()
                        + " 的 home 数据源 '" + home + "' 未在任何执行器注册；已注册: " + registered
                        + "。请在执行器的 DataSourceRegistry 注册该数据源，或修正 @Table/@Document(dataSource=...) / dataSource(...) 绑定。");
            }
        }
    }

    /**
     * 扫描指定包,自动装配实体与 Repository,等价于"手动列出实体类 + 逐个 getRepository"的简化版。
     *
     * <p>规则:被某个已注册 {@link EntityMetadataResolver} 支持的<b>具体类</b>作为实体注册;被某个已注册
     * {@link RepositoryFactory} 支持的<b>接口</b>实例化为 Repository。实体识别与 Repository 识别都复用
     * 既有 SPI,因此自动覆盖 RDB / DocDB / 缓存等所有已安装模块,无需在这里硬编码类型。</p>
     *
     * <p>必须在 {@code install(...)} 之后调用(resolver / factory 需先就位)。框架不依赖任何 DI 容器,
     * 返回的 {@link GameJpaScan} 让宿主自行把 Repository 实例注册进 Spring 等容器。</p>
     *
     * @param basePackages 需要扫描的包名(含子包),用线程上下文 ClassLoader 加载
     */
    public GameJpaScan scanPackages(String... basePackages) {
        return scanPackages(null, basePackages);
    }

    /** {@link #scanPackages(String...)} 的显式 ClassLoader 版本。 */
    public GameJpaScan scanPackages(ClassLoader classLoader, String... basePackages) {
        List<Class<?>> entityClasses = new ArrayList<>();
        List<Class<?>> repositoryInterfaces = new ArrayList<>();
        for (String basePackage : basePackages) {
            for (Class<?> candidate : ClasspathScanner.scan(basePackage, classLoader)) {
                if (candidate.isInterface()) {
                    if (supportsRepository(candidate)) {
                        repositoryInterfaces.add(candidate);
                    } else if (candidate.isAnnotationPresent(GameRepository.class)) {
                        // 显式声明了 @GameRepository 却无人支持:多半继承错基接口或对应模块没 install。
                        throw new IllegalStateException("@GameRepository 接口 " + candidate.getName()
                                + " 没有任何已安装模块的 RepositoryFactory 支持;请检查它是否继承了正确的 "
                                + "Repository 基接口,以及对应存储模块是否已 install。");
                    }
                } else if (supportsEntity(candidate)) {
                    entityClasses.add(candidate);
                }
            }
        }

        GameJpaContext context = bootstrap(entityClasses);

        Map<Class<?>, Object> repositories = new LinkedHashMap<>();
        for (Class<?> repositoryType : repositoryInterfaces) {
            repositories.put(repositoryType, context.getRepository(repositoryType));
        }
        return new GameJpaScan(context, repositories);
    }

    private boolean supportsEntity(Class<?> candidate) {
        for (EntityMetadataResolver<?> resolver : resolvers) {
            if (resolver.supports(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean supportsRepository(Class<?> candidate) {
        for (RepositoryFactory factory : repositoryFactories) {
            if (factory.supports(candidate)) {
                return true;
            }
        }
        return false;
    }

    public MetadataRegistry metadataRegistry() {
        return metadataRegistry;
    }

    public TypeConverterRegistry converterRegistry() {
        return converterRegistry;
    }

    public LifecycleDispatcher lifecycleDispatcher() {
        return lifecycleDispatcher;
    }
}
