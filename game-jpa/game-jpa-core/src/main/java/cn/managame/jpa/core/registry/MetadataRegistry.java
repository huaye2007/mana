package cn.managame.jpa.core.registry;

import cn.managame.jpa.core.bootstrap.ModelType;
import cn.managame.jpa.core.metadata.EntityMetadata;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 元数据注册中心。
 * 启动期注册，运行期只读。
 */
public class MetadataRegistry {

    private final Map<Class<?>, EntityMetadata> byEntityType = new ConcurrentHashMap<>();
    private final Map<ModelType, List<EntityMetadata>> byModelType = new ConcurrentHashMap<>();

    public void register(EntityMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        EntityMetadata previous = byEntityType.put(metadata.entityType(), metadata);
        if (previous != null) {
            List<EntityMetadata> previousList = byModelType.get(previous.modelType());
            if (previousList != null) {
                previousList.removeIf(item -> item.entityType().equals(metadata.entityType()));
            }
        }
        byModelType.computeIfAbsent(metadata.modelType(), k -> new CopyOnWriteArrayList<>())
                .add(metadata);
    }

    public Optional<EntityMetadata> get(Class<?> entityType) {
        return Optional.ofNullable(byEntityType.get(entityType));
    }

    @SuppressWarnings("unchecked")
    public <M extends EntityMetadata> Optional<M> get(Class<?> entityType, Class<M> metadataType) {
        EntityMetadata metadata = byEntityType.get(entityType);
        if (metadata != null && metadataType.isInstance(metadata)) {
            return Optional.of((M) metadata);
        }
        return Optional.empty();
    }

    public Collection<EntityMetadata> getAll() {
        return byEntityType.values();
    }

    public List<EntityMetadata> getByModel(ModelType modelType) {
        return byModelType.getOrDefault(modelType, List.of());
    }
}
