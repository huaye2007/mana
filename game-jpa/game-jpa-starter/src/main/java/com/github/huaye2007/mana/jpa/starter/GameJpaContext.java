package com.github.huaye2007.mana.jpa.starter;

import com.github.huaye2007.mana.jpa.async.AsyncWriteQueue;
import com.github.huaye2007.mana.jpa.async.FlushScheduler;
import com.github.huaye2007.mana.jpa.core.context.ComponentRegistry;
import com.github.huaye2007.mana.jpa.core.converter.TypeConverterRegistry;
import com.github.huaye2007.mana.jpa.core.lifecycle.LifecycleDispatcher;
import com.github.huaye2007.mana.jpa.core.metrics.MetricsCollector;
import com.github.huaye2007.mana.jpa.core.registry.MetadataRegistry;
import com.github.huaye2007.mana.jpa.core.repository.RepositoryFactory;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategy;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategyRegistry;
import com.github.huaye2007.mana.jpa.core.write.WriteChannelRegistry;
import com.github.huaye2007.mana.jpa.core.write.WriteTaskSubmitter;

import java.io.Closeable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Framework runtime context implementing {@link ComponentRegistry}.
 */
public class GameJpaContext implements ComponentRegistry, Closeable {

    private static final Logger log = LoggerFactory.getLogger(GameJpaContext.class);

    private final List<RepositoryFactory> repositoryFactories;
    private final Map<Class<?>, Object> repositoryCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> components = new ConcurrentHashMap<>();
    private final List<Closeable> managedResources = new ArrayList<>();
    private volatile boolean closed = false;

    public GameJpaContext(MetadataRegistry metadataRegistry,
                          TypeConverterRegistry converterRegistry,
                          LifecycleDispatcher lifecycleDispatcher,
                          AsyncWriteQueue writeQueue,
                          FlushScheduler flushScheduler,
                          List<RepositoryFactory> repositoryFactories,
                          MetricsCollector metricsCollector,
                          RoutingStrategy routingStrategy) {
        this(metadataRegistry, converterRegistry, lifecycleDispatcher, writeQueue, flushScheduler,
                repositoryFactories, metricsCollector,
                new RoutingStrategyRegistry().defaultStrategy(routingStrategy));
    }

    GameJpaContext(MetadataRegistry metadataRegistry,
                   TypeConverterRegistry converterRegistry,
                   LifecycleDispatcher lifecycleDispatcher,
                   AsyncWriteQueue writeQueue,
                   FlushScheduler flushScheduler,
                   List<RepositoryFactory> repositoryFactories,
                   MetricsCollector metricsCollector,
                   RoutingStrategyRegistry routingStrategyRegistry) {
        this.repositoryFactories = repositoryFactories;

        // Register core components into ComponentRegistry.
        register(MetadataRegistry.class, metadataRegistry);
        register(TypeConverterRegistry.class, converterRegistry);
        register(LifecycleDispatcher.class, lifecycleDispatcher);
        register(MetricsCollector.class, metricsCollector);
        register(AsyncWriteQueue.class, writeQueue);
        register(WriteTaskSubmitter.class, writeQueue); // AsyncWriteQueue implements WriteTaskSubmitter
        register(WriteChannelRegistry.class, writeQueue); // 提交期路由需通道里的 router，故注册表在队列侧
        register(FlushScheduler.class, flushScheduler);
        register(RoutingStrategyRegistry.class, routingStrategyRegistry);
        if (routingStrategyRegistry.defaultStrategy() != null) {
            register(RoutingStrategy.class, routingStrategyRegistry.defaultStrategy());
        }
        register(ComponentRegistry.class, this);

        addManagedResource(writeQueue);
        addManagedResource(flushScheduler);
    }

    // ==================== ComponentRegistry ====================

    @Override
    public <T> void register(Class<T> type, T component) {
        components.put(type, component);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        T component = (T) components.get(type);
        if (component == null) {
            throw new IllegalArgumentException("Component not found: " + type.getName());
        }
        return component;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T find(Class<T> type) {
        return (T) components.get(type);
    }

    /** 按可赋值类型查找所有组件（去重，同一实例只返回一次），用于按接口收集如 DataSourceCatalog 的执行器。 */
    @SuppressWarnings("unchecked")
    public <T> List<T> findAllAssignable(Class<T> type) {
        List<T> result = new ArrayList<>();
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object component : components.values()) {
            if (type.isInstance(component) && seen.add(component)) {
                result.add((T) component);
            }
        }
        return result;
    }

    // ==================== Repository access ====================

    @SuppressWarnings("unchecked")
    public <R> R getRepository(Class<R> repositoryType) {
        return (R) repositoryCache.computeIfAbsent(repositoryType, type -> {
            RepositoryFactory selected = null;
            for (RepositoryFactory factory : repositoryFactories) {
                if (factory.supports(type)) {
                    if (selected == null || factory.priority() > selected.priority()) {
                        selected = factory;
                    }
                }
            }
            if (selected != null) {
                Object repository = selected.createRepository(type, this);
                return adaptRepository(type, repository);
            }
            throw new IllegalArgumentException("No RepositoryFactory supports: " + type.getName());
        });
    }

    private Object adaptRepository(Class<?> repositoryType, Object repository) {
        if (repositoryType.isInstance(repository)) {
            return repository;
        }
        if (!repositoryType.isInterface()) {
            return repository;
        }
        // Resolve the interface-method -> impl-method mapping once at proxy creation
        // time. This also validates the contract (every non-default interface method
        // must have a concrete impl) and removes the per-call reflective getMethod
        // lookup from the repository hot path.
        Map<Method, Method> methodCache = buildMethodCache(repositoryType, repository);
        return Proxy.newProxyInstance(
                repositoryType.getClassLoader(),
                new Class<?>[] { repositoryType },
                (proxy, method, args) -> invokeRepositoryMethod(proxy, repository, methodCache, method, args));
    }

    private Map<Method, Method> buildMethodCache(Class<?> repositoryType, Object repository) {
        Map<Method, Method> methodCache = new HashMap<>();
        for (Method method : repositoryType.getMethods()) {
            if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            try {
                // A concrete impl method (including an override of an interface default
                // method) always wins over the interface default body, matching the
                // pre-cache getMethod-first resolution.
                methodCache.put(method,
                        repository.getClass().getMethod(method.getName(), method.getParameterTypes()));
            } catch (NoSuchMethodException e) {
                if (method.isDefault()) {
                    continue; // no impl override; dispatched via invokeDefault at call time
                }
                throw new IllegalArgumentException("Repository method is not implemented: "
                        + repositoryType.getName() + "#" + method.getName(), e);
            }
        }
        return methodCache;
    }

    private Object invokeRepositoryMethod(Object proxy, Object repository, Map<Method, Method> methodCache,
            Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> repository.toString();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> method.invoke(repository, args);
            };
        }
        Method targetMethod = methodCache.get(method);
        if (targetMethod != null) {
            try {
                return targetMethod.invoke(repository, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args == null ? new Object[0] : args);
        }
        throw new UnsupportedOperationException("Repository method is not implemented: " + method);
    }

    // ==================== Convenience access ====================

    public MetadataRegistry metadataRegistry() { return get(MetadataRegistry.class); }
    public TypeConverterRegistry converterRegistry() { return get(TypeConverterRegistry.class); }
    public LifecycleDispatcher lifecycleDispatcher() { return get(LifecycleDispatcher.class); }
    public MetricsCollector metricsCollector() { return get(MetricsCollector.class); }
    public RoutingStrategy routingStrategy() { return find(RoutingStrategy.class); }
    public RoutingStrategyRegistry routingStrategyRegistry() { return get(RoutingStrategyRegistry.class); }
    public AsyncWriteQueue writeQueue() { return get(AsyncWriteQueue.class); }
    public FlushScheduler flushScheduler() { return get(FlushScheduler.class); }

    // ==================== Resource management ====================

    public void addManagedResource(Closeable resource) {
        if (!(resource instanceof FlushScheduler)) {
            for (int i = 0; i < managedResources.size(); i++) {
                if (managedResources.get(i) instanceof FlushScheduler) {
                    managedResources.add(i, resource);
                    return;
                }
            }
        }
        managedResources.add(resource);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        log.info("[GameJpa] Shutting down...");
        AsyncWriteQueue writeQueue = find(AsyncWriteQueue.class);
        if (writeQueue != null) {
            writeQueue.closeForSubmissions();
        }

        for (int i = managedResources.size() - 1; i >= 0; i--) {
            try {
                managedResources.get(i).close();
            } catch (Exception e) {
                log.warn("[GameJpa] Error closing resource: {}", e.getMessage(), e);
            }
        }
        log.info("[GameJpa] Shutdown complete.");
    }

    public boolean isClosed() {
        return closed;
    }
}
