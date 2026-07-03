package com.github.huaye2007.mana.jpa.rdb.repository;

import com.github.huaye2007.mana.jpa.core.bootstrap.ModelTypes;
import com.github.huaye2007.mana.jpa.core.context.ComponentRegistry;
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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for append-only log repositories.
 * <p>
 * 日志走异步追加管道：注册一个 {@link WriteChannel.Append} 追加通道（路由器从实体 {@code @ShardKey}
 * 解析分表目标，落库器整批 {@code batchInsert}），{@link DefaultRdbLogRepository} 仅向异步队列提交追加。
 */
public class RdbLogRepositoryFactory implements RepositoryFactory {

    private final Set<String> registeredChannels = ConcurrentHashMap.newKeySet();

    @Override
    public boolean supports(Class<?> repositoryType) {
        return RdbLogRepository.class.isAssignableFrom(repositoryType);
    }

    @Override
    public Object createRepository(Class<?> repositoryType, ComponentRegistry registry) {
        Class<?> entityType = RepositoryTypes.resolveEntityType(repositoryType, RdbLogRepository.class);
        RdbEntityMetadata metadata = registry.get(MetadataRegistry.class)
                .get(entityType, RdbEntityMetadata.class)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No RDB metadata found for entity: " + entityType.getName()));

        WriteTaskSubmitter submitter = registry.get(WriteTaskSubmitter.class);
        String writeEntityName = writeEntityName(metadata);
        if (registeredChannels.add(writeEntityName)) {
            WriteChannelRegistry channelRegistry = registry.get(WriteChannelRegistry.class);
            RdbExecutor executor = registry.get(RdbExecutor.class);
            MetricsCollector metrics = registry.get(MetricsCollector.class);
            RoutingStrategy routingStrategy = resolveRoutingStrategy(registry, metadata);
            RdbLogBatchFlusher flusher = new RdbLogBatchFlusher(metadata, executor, metrics);
            String homeDataSource = DataSourceBinding.resolveHomeDataSource(registry, metadata);
            ShardWriteRouter router = new ShardWriteRouter(metadata, routingStrategy, homeDataSource, "RDB");
            channelRegistry.register(new WriteChannel.Append(writeEntityName, router, flusher));
        }
        return new DefaultRdbLogRepository<>(submitter, writeEntityName);
    }

    private String writeEntityName(RdbEntityMetadata metadata) {
        return ModelTypes.RDB.modelName() + ":" + metadata.entityType().getName();
    }

    private RoutingStrategy resolveRoutingStrategy(ComponentRegistry registry, RdbEntityMetadata metadata) {
        RoutingStrategyRegistry routingRegistry = registry.find(RoutingStrategyRegistry.class);
        if (routingRegistry != null) {
            return routingRegistry.resolve(metadata.entityType(), metadata.logicalName());
        }
        return registry.find(RoutingStrategy.class);
    }
}
