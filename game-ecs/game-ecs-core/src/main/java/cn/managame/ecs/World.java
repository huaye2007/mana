package cn.managame.ecs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Mutable ECS container. A world is intentionally not thread-safe; route all access for one
 * world to the same game-runtime router key, or otherwise confine it to one thread.
 */
public final class World {

    private final Set<Long> entities = new HashSet<>();
    private final Map<Class<? extends Component>, Map<Long, Component>> stores = new HashMap<>();
    private final List<WorldListener> listeners = new ArrayList<>();
    private long nextEntityId = 1;

    public Entity createEntity() {
        if (nextEntityId == Long.MAX_VALUE) {
            throw new IllegalStateException("entity id space exhausted");
        }
        Entity entity = new Entity(nextEntityId++);
        entities.add(entity.id());
        listeners.forEach(listener -> listener.onEntityCreated(entity));
        return entity;
    }

    public boolean deleteEntity(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (!entities.remove(entity.id())) {
            return false;
        }
        for (Map<Long, Component> store : stores.values()) {
            Component removed = store.remove(entity.id());
            if (removed != null) {
                listeners.forEach(listener -> listener.onComponentRemoved(entity, removed));
            }
        }
        listeners.forEach(listener -> listener.onEntityDeleted(entity));
        return true;
    }

    public boolean isAlive(Entity entity) {
        return entity != null && entities.contains(entity.id());
    }

    public int entityCount() {
        return entities.size();
    }

    public <T extends Component> Optional<T> add(Entity entity, T component) {
        requireAlive(entity);
        Objects.requireNonNull(component, "component");
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) component.getClass();
        Map<Long, Component> store = stores.computeIfAbsent(type, ignored -> new HashMap<>());
        Component previous = store.put(entity.id(), component);
        if (previous != null) {
            listeners.forEach(listener -> listener.onComponentRemoved(entity, previous));
        }
        listeners.forEach(listener -> listener.onComponentAdded(entity, component));
        return Optional.ofNullable(type.cast(previous));
    }

    public <T extends Component> Optional<T> get(Entity entity, Class<T> type) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(type, "type");
        Map<Long, Component> store = stores.get(type);
        return store == null ? Optional.empty() : Optional.ofNullable(type.cast(store.get(entity.id())));
    }

    public <T extends Component> boolean has(Entity entity, Class<T> type) {
        return get(entity, type).isPresent();
    }

    public <T extends Component> Optional<T> remove(Entity entity, Class<T> type) {
        requireAlive(entity);
        Objects.requireNonNull(type, "type");
        Map<Long, Component> store = stores.get(type);
        if (store == null) {
            return Optional.empty();
        }
        T removed = type.cast(store.remove(entity.id()));
        if (removed != null) {
            listeners.forEach(listener -> listener.onComponentRemoved(entity, removed));
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Returns an immutable snapshot sorted by entity ID. The smallest component store drives
     * the intersection, so sparse queries avoid scanning every entity.
     */
    @SafeVarargs
    public final List<Entity> query(Class<? extends Component>... componentTypes) {
        Objects.requireNonNull(componentTypes, "componentTypes");
        if (componentTypes.length == 0) {
            return entities.stream().sorted().map(Entity::new).toList();
        }
        List<Map<Long, Component>> required = new ArrayList<>(componentTypes.length);
        for (Class<? extends Component> type : componentTypes) {
            Objects.requireNonNull(type, "component type");
            Map<Long, Component> store = stores.get(type);
            if (store == null || store.isEmpty()) {
                return List.of();
            }
            required.add(store);
        }
        Map<Long, Component> smallest = required.stream()
                .min(Comparator.comparingInt(Map::size)).orElseThrow();
        return smallest.keySet().stream()
                .filter(entities::contains)
                .filter(id -> required.stream().allMatch(store -> store.containsKey(id)))
                .sorted()
                .map(Entity::new)
                .toList();
    }

    public CommandBuffer commandBuffer() {
        return new CommandBuffer(this);
    }

    public void addListener(WorldListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public boolean removeListener(WorldListener listener) {
        return listeners.remove(listener);
    }

    private void requireAlive(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (!entities.contains(entity.id())) {
            throw new IllegalArgumentException("entity is not alive in this world: " + entity.id());
        }
    }
}
