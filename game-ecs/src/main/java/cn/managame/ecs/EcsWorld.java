package cn.managame.ecs;

import cn.managame.ecs.change.FieldChange;
import cn.managame.ecs.change.WorldChangeBatch;
import cn.managame.ecs.change.WorldChangeSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Owns ECS entities, components, queries, systems, and deferred structural
 * changes for one game simulation.
 *
 * <p>A world is intentionally single-threaded. A game server should route all
 * access to the same world through one logical simulation thread, for example
 * through a {@code game-runtime} router key.</p>
 */
public final class EcsWorld implements AutoCloseable {

    private final Map<Long, EntityState> entities = new LinkedHashMap<>();
    private final Map<Class<? extends Component>, LinkedHashMap<Long, Component>> componentIndexes =
            new HashMap<>();
    private final List<SystemRegistration> systems = new ArrayList<>();
    private final Set<EntitySystem> registeredSystems =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final WorldCommandBuffer commands = new WorldCommandBuffer(this);
    private final List<WorldChangeSink> changeSinks = new ArrayList<>();

    private long nextEntityId = 1;
    private long nextSystemSequence;
    private WorldChangeJournal changeJournal;
    private boolean updating;
    private boolean closed;

    /**
     * Creates an empty entity.
     */
    public Entity createEntity() {
        ensureOpen();
        return createReservedEntity(reserveEntityId());
    }

    /**
     * Finds a live entity by id.
     */
    public Optional<Entity> findEntity(EntityId id) {
        Objects.requireNonNull(id, "id");
        if (closed) {
            return Optional.empty();
        }
        EntityState state = entities.get(id.value());
        return state == null ? Optional.empty() : Optional.of(state.handle);
    }

    /**
     * Returns a live entity by id.
     *
     * @throws IllegalStateException if no live entity has the id
     */
    public Entity requireEntity(EntityId id) {
        ensureOpen();
        return state(id).handle;
    }

    /**
     * Returns whether an entity is alive in this world.
     */
    public boolean isAlive(EntityId id) {
        Objects.requireNonNull(id, "id");
        return !closed && entities.containsKey(id.value());
    }

    /**
     * Destroys an entity and removes all its components.
     *
     * @return {@code true} if the entity existed
     */
    public boolean destroyEntity(EntityId id) {
        ensureOpen();
        Objects.requireNonNull(id, "id");
        EntityState removed = entities.remove(id.value());
        if (removed == null) {
            return false;
        }

        for (Class<? extends Component> type : removed.components.keySet()) {
            LinkedHashMap<Long, Component> index = componentIndexes.get(type);
            if (index != null) {
                index.remove(id.value());
                if (index.isEmpty()) {
                    componentIndexes.remove(type);
                }
            }
        }
        recordEntityDestroyed(id);
        return true;
    }

    /**
     * Returns the number of live entities.
     */
    public int entityCount() {
        ensureOpen();
        return entities.size();
    }

    /**
     * Creates a reusable query matching all specified component types.
     *
     * <p>An empty query matches every live entity.</p>
     */
    @SafeVarargs
    public final EntityQuery query(Class<? extends Component>... requiredTypes) {
        ensureOpen();
        Objects.requireNonNull(requiredTypes, "requiredTypes");
        LinkedHashSet<Class<? extends Component>> distinct = new LinkedHashSet<>();
        for (Class<? extends Component> type : requiredTypes) {
            distinct.add(Objects.requireNonNull(type, "required component type"));
        }
        return new EntityQuery(this, List.copyOf(distinct));
    }

    /**
     * Registers a system. Lower order values execute first; ties preserve
     * registration order.
     */
    public EcsWorld addSystem(EntitySystem system) {
        ensureNotUpdating("register systems");
        Objects.requireNonNull(system, "system");
        if (!registeredSystems.add(system)) {
            throw new IllegalStateException("System instance is already registered: " + system);
        }

        SystemRegistration registration =
                new SystemRegistration(system, system.order(), nextSystemSequence++);
        systems.add(registration);
        systems.sort(SystemRegistration.ORDERING);
        try {
            system.onAdded(this);
        } catch (RuntimeException | Error failure) {
            systems.remove(registration);
            registeredSystems.remove(system);
            throw failure;
        }
        return this;
    }

    /**
     * Unregisters a system by identity.
     *
     * @return {@code true} if the system was registered
     */
    public boolean removeSystem(EntitySystem system) {
        ensureNotUpdating("remove systems");
        Objects.requireNonNull(system, "system");
        if (!registeredSystems.remove(system)) {
            return false;
        }

        systems.removeIf(registration -> registration.system == system);
        system.onRemoved(this);
        return true;
    }

    /**
     * Returns an immutable snapshot of systems in execution order.
     */
    public List<EntitySystem> systems() {
        ensureOpen();
        return systems.stream().map(registration -> registration.system).toList();
    }

    /**
     * Advances every registered system once and then flushes the command
     * buffer.
     *
     * @param deltaSeconds finite, non-negative simulation delta in seconds
     */
    public void update(double deltaSeconds) {
        ensureOpen();
        if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0) {
            throw new IllegalArgumentException(
                    "deltaSeconds must be finite and non-negative: " + deltaSeconds);
        }
        if (updating) {
            throw new IllegalStateException("Nested world updates are not supported");
        }

        Throwable failure = null;
        updating = true;
        try {
            for (SystemRegistration registration : systems) {
                registration.system.update(this, deltaSeconds);
            }
        } catch (RuntimeException | Error systemFailure) {
            failure = systemFailure;
        } finally {
            updating = false;
            try {
                commands.flushInternal();
            } catch (RuntimeException | Error flushFailure) {
                if (failure == null) {
                    failure = flushFailure;
                } else {
                    failure.addSuppressed(flushFailure);
                }
            }
            try {
                publishChangesInternal();
            } catch (RuntimeException | Error publishFailure) {
                if (failure == null) {
                    failure = publishFailure;
                } else {
                    failure.addSuppressed(publishFailure);
                }
            }
        }

        rethrow(failure);
    }

    /**
     * Returns this world's structural-change command buffer.
     */
    public WorldCommandBuffer commands() {
        ensureOpen();
        return commands;
    }

    /**
     * Applies all currently deferred structural changes.
     *
     * <p>Updates flush automatically. This method is useful when commands were
     * enqueued outside a system update.</p>
     */
    public void flushCommands() {
        ensureNotUpdating("flush commands");
        commands.flushInternal();
    }

    /**
     * Enables client-observable change tracking for future mutations.
     *
     * <p>Tracking is opt-in so server-only worlds do not retain unsent change
     * history. Adding a change sink also enables tracking automatically.</p>
     */
    public EcsWorld enableChangeTracking() {
        ensureNotUpdating("enable change tracking");
        if (changeJournal == null) {
            changeJournal = new WorldChangeJournal();
        }
        return this;
    }

    /**
     * Returns whether change tracking is enabled.
     */
    public boolean isChangeTrackingEnabled() {
        ensureOpen();
        return changeJournal != null;
    }

    /**
     * Registers a synchronous tick-end change sink and enables tracking.
     *
     * <p>The sink should enqueue serialized outbound data and return quickly.
     * Register sinks before creating client-visible entities.</p>
     */
    public EcsWorld addChangeSink(WorldChangeSink sink) {
        ensureNotUpdating("register change sinks");
        Objects.requireNonNull(sink, "sink");
        for (WorldChangeSink registered : changeSinks) {
            if (registered == sink) {
                throw new IllegalStateException("Change sink is already registered: " + sink);
            }
        }
        enableChangeTracking();
        changeSinks.add(sink);
        return this;
    }

    /**
     * Removes a change sink by identity.
     */
    public boolean removeChangeSink(WorldChangeSink sink) {
        ensureNotUpdating("remove change sinks");
        Objects.requireNonNull(sink, "sink");
        for (int index = 0; index < changeSinks.size(); index++) {
            if (changeSinks.get(index) == sink) {
                changeSinks.remove(index);
                return true;
            }
        }
        return false;
    }

    /**
     * Drains the currently pending coalesced changes for pull-based delivery.
     *
     * <p>The result is empty when tracking is disabled, no changes exist, or
     * all pending changes cancel out.</p>
     */
    public Optional<WorldChangeBatch> drainChanges() {
        ensureNotUpdating("drain changes");
        return changeJournal == null ? Optional.empty() : changeJournal.drain();
    }

    /**
     * Publishes pending changes to every registered sink immediately.
     *
     * @return {@code true} if a non-empty batch was published
     */
    public boolean publishChanges() {
        ensureNotUpdating("publish changes");
        return publishChangesInternal();
    }

    /**
     * Returns the raw number of changes waiting to be coalesced.
     */
    public int pendingChangeCount() {
        ensureOpen();
        return changeJournal == null ? 0 : changeJournal.pendingCount();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        ensureNotUpdating("close the world");

        Throwable failure = null;
        List<SystemRegistration> reverse = new ArrayList<>(systems);
        Collections.reverse(reverse);
        for (SystemRegistration registration : reverse) {
            try {
                registration.system.onRemoved(this);
            } catch (RuntimeException | Error lifecycleFailure) {
                if (failure == null) {
                    failure = lifecycleFailure;
                } else {
                    failure.addSuppressed(lifecycleFailure);
                }
            }
        }

        commands.clear();
        if (changeJournal != null) {
            changeJournal.clear();
        }
        changeSinks.clear();
        systems.clear();
        registeredSystems.clear();
        componentIndexes.clear();
        entities.clear();
        closed = true;
        rethrow(failure);
    }

    EntityId reserveEntityId() {
        ensureOpen();
        if (nextEntityId == Long.MAX_VALUE) {
            throw new IllegalStateException("Entity id space is exhausted");
        }
        return new EntityId(nextEntityId++);
    }

    Entity createReservedEntity(EntityId id) {
        ensureOpen();
        Objects.requireNonNull(id, "id");
        if (entities.containsKey(id.value())) {
            throw new IllegalStateException("Entity already exists: " + id);
        }
        Entity handle = new Entity(this, id);
        entities.put(id.value(), new EntityState(handle));
        recordEntityCreated(id);
        return handle;
    }

    void putInferredComponent(EntityId id, Component component) {
        @SuppressWarnings("unchecked")
        Class<Component> type = (Class<Component>) component.getClass();
        putComponent(id, type, component);
    }

    <T extends Component> void putComponent(EntityId id, Class<T> type, T component) {
        ensureOpen();
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(component, "component");
        if (!type.isInstance(component)) {
            throw new IllegalArgumentException(
                    "Component " + component.getClass().getName()
                            + " is not an instance of " + type.getName());
        }

        EntityState state = state(id);
        Component previous = state.components.put(type, component);
        componentIndexes.computeIfAbsent(type, ignored -> new LinkedHashMap<>())
                .put(id.value(), component);
        if (previous == null) {
            recordComponentAdded(id, type, component);
        } else if (!Objects.deepEquals(previous, component)) {
            recordComponentReplaced(id, type, previous, component);
        }
    }

    <T extends Component> Optional<T> findComponent(EntityId id, Class<T> type) {
        ensureOpen();
        Objects.requireNonNull(type, "type");
        Component component = state(id).components.get(type);
        return component == null ? Optional.empty() : Optional.of(type.cast(component));
    }

    boolean hasComponent(EntityId id, Class<? extends Component> type) {
        ensureOpen();
        Objects.requireNonNull(type, "type");
        return state(id).components.containsKey(type);
    }

    <T extends Component> Optional<T> removeComponent(EntityId id, Class<T> type) {
        ensureOpen();
        Objects.requireNonNull(type, "type");
        Component removed = state(id).components.remove(type);
        if (removed == null) {
            return Optional.empty();
        }

        LinkedHashMap<Long, Component> index = componentIndexes.get(type);
        if (index != null) {
            index.remove(id.value());
            if (index.isEmpty()) {
                componentIndexes.remove(type);
            }
        }
        recordComponentRemoved(id, type, removed);
        return Optional.of(type.cast(removed));
    }

    <T extends Component, V> void updateField(
            EntityId id,
            Class<T> componentType,
            String fieldName,
            Function<? super T, ? extends V> getter,
            BiConsumer<? super T, ? super V> setter,
            V newValue) {
        ensureOpen();
        Objects.requireNonNull(componentType, "componentType");
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(getter, "getter");
        Objects.requireNonNull(setter, "setter");

        T component = requiredComponent(id, componentType);
        V previousValue = getter.apply(component);
        setter.accept(component, newValue);
        V currentValue = getter.apply(component);
        markFieldChanged(
                id, componentType, fieldName, previousValue, currentValue);
    }

    void markFieldChanged(
            EntityId id,
            Class<? extends Component> componentType,
            String fieldName,
            Object previousValue,
            Object currentValue) {
        ensureOpen();
        Objects.requireNonNull(componentType, "componentType");
        FieldChange field = new FieldChange(
                fieldName, previousValue, currentValue);
        Component component = requiredComponent(id, componentType);
        if (changeJournal != null
                && !Objects.deepEquals(previousValue, currentValue)) {
            changeJournal.fieldUpdated(id, componentType, component, field);
        }
    }

    Set<Class<? extends Component>> componentTypes(EntityId id) {
        ensureOpen();
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(state(id).components.keySet()));
    }

    List<Entity> querySnapshot(List<Class<? extends Component>> requiredTypes) {
        ensureOpen();
        Objects.requireNonNull(requiredTypes, "requiredTypes");
        if (requiredTypes.isEmpty()) {
            return entities.values().stream()
                    .map(state -> state.handle)
                    .sorted(Comparator.comparingLong(entity -> entity.id().value()))
                    .toList();
        }

        LinkedHashMap<Long, Component> smallestIndex = null;
        for (Class<? extends Component> type : requiredTypes) {
            LinkedHashMap<Long, Component> index = componentIndexes.get(type);
            if (index == null) {
                return List.of();
            }
            if (smallestIndex == null || index.size() < smallestIndex.size()) {
                smallestIndex = index;
            }
        }

        List<Entity> result = new ArrayList<>(smallestIndex.size());
        for (Long id : smallestIndex.keySet()) {
            EntityState state = entities.get(id);
            if (state != null && state.components.keySet().containsAll(requiredTypes)) {
                result.add(state.handle);
            }
        }
        result.sort(Comparator.comparingLong(entity -> entity.id().value()));
        return List.copyOf(result);
    }

    int queryCount(List<Class<? extends Component>> requiredTypes) {
        return querySnapshot(requiredTypes).size();
    }

    void ensureCommandBufferOpen() {
        ensureOpen();
    }

    private <T extends Component> T requiredComponent(
            EntityId id, Class<T> componentType) {
        Component component = state(id).components.get(componentType);
        if (component == null) {
            throw new IllegalStateException(
                    "Entity " + id + " does not contain component "
                            + componentType.getName());
        }
        return componentType.cast(component);
    }

    private void recordEntityCreated(EntityId id) {
        if (changeJournal != null) {
            changeJournal.entityCreated(id);
        }
    }

    private void recordEntityDestroyed(EntityId id) {
        if (changeJournal != null) {
            changeJournal.entityDestroyed(id);
        }
    }

    private void recordComponentAdded(
            EntityId id,
            Class<? extends Component> componentType,
            Component component) {
        if (changeJournal != null) {
            changeJournal.componentAdded(id, componentType, component);
        }
    }

    private void recordComponentReplaced(
            EntityId id,
            Class<? extends Component> componentType,
            Component previous,
            Component current) {
        if (changeJournal != null) {
            changeJournal.componentReplaced(
                    id, componentType, previous, current);
        }
    }

    private void recordComponentRemoved(
            EntityId id,
            Class<? extends Component> componentType,
            Component previous) {
        if (changeJournal != null) {
            changeJournal.componentRemoved(id, componentType, previous);
        }
    }

    private boolean publishChangesInternal() {
        if (changeJournal == null || changeSinks.isEmpty()) {
            return false;
        }
        Optional<WorldChangeBatch> pendingBatch = changeJournal.drain();
        if (pendingBatch.isEmpty()) {
            return false;
        }

        WorldChangeBatch batch = pendingBatch.orElseThrow();
        Throwable failure = null;
        for (WorldChangeSink sink : List.copyOf(changeSinks)) {
            try {
                sink.accept(batch);
            } catch (RuntimeException | Error sinkFailure) {
                if (failure == null) {
                    failure = sinkFailure;
                } else {
                    failure.addSuppressed(sinkFailure);
                }
            }
        }
        rethrow(failure);
        return true;
    }

    private EntityState state(EntityId id) {
        Objects.requireNonNull(id, "id");
        EntityState state = entities.get(id.value());
        if (state == null) {
            throw new IllegalStateException("Entity is not alive: " + id);
        }
        return state;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("ECS world is closed");
        }
    }

    private void ensureNotUpdating(String operation) {
        ensureOpen();
        if (updating) {
            throw new IllegalStateException("Cannot " + operation + " during a world update");
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static final class EntityState {

        private final Entity handle;
        private final Map<Class<? extends Component>, Component> components =
                new LinkedHashMap<>();

        private EntityState(Entity handle) {
            this.handle = handle;
        }
    }

    private record SystemRegistration(EntitySystem system, int order, long sequence) {

        private static final Comparator<SystemRegistration> ORDERING =
                Comparator.comparingInt(SystemRegistration::order)
                        .thenComparingLong(SystemRegistration::sequence);
    }
}
