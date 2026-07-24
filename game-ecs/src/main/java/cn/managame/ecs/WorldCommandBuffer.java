package cn.managame.ecs;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * FIFO buffer for structural ECS changes.
 *
 * <p>Commands enqueued by systems are applied automatically after all systems
 * finish the current tick. This gives every system in a tick a stable view of
 * entity structure. Commands enqueued outside a tick can be applied with
 * {@link EcsWorld#flushCommands()}.</p>
 */
public final class WorldCommandBuffer {

    private final EcsWorld world;
    private final Deque<Runnable> pending = new ArrayDeque<>();
    private boolean flushing;

    WorldCommandBuffer(EcsWorld world) {
        this.world = world;
    }

    /**
     * Defers creation of an entity.
     *
     * <p>The id is reserved immediately. The initializer runs when the buffer
     * is flushed, after the entity has been inserted into the world.</p>
     *
     * @return reserved id of the future entity
     */
    public EntityId createEntity(Consumer<Entity> initializer) {
        world.ensureCommandBufferOpen();
        Objects.requireNonNull(initializer, "initializer");
        EntityId id = world.reserveEntityId();
        pending.addLast(() -> {
            Entity entity = world.createReservedEntity(id);
            try {
                initializer.accept(entity);
            } catch (RuntimeException | Error failure) {
                world.destroyEntity(id);
                throw failure;
            }
        });
        return id;
    }

    /**
     * Defers destruction of an entity.
     */
    public WorldCommandBuffer destroyEntity(EntityId id) {
        world.ensureCommandBufferOpen();
        Objects.requireNonNull(id, "id");
        pending.addLast(() -> world.destroyEntity(id));
        return this;
    }

    /**
     * Defers adding or replacing a component using its concrete runtime class.
     */
    public WorldCommandBuffer addComponent(EntityId id, Component component) {
        world.ensureCommandBufferOpen();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(component, "component");
        pending.addLast(() -> world.putInferredComponent(id, component));
        return this;
    }

    /**
     * Defers adding or replacing a component under an explicit type.
     */
    public <T extends Component> WorldCommandBuffer addComponent(
            EntityId id, Class<T> type, T component) {
        world.ensureCommandBufferOpen();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(component, "component");
        pending.addLast(() -> world.putComponent(id, type, component));
        return this;
    }

    /**
     * Defers removal of a component stored under the exact requested type.
     */
    public WorldCommandBuffer removeComponent(
            EntityId id, Class<? extends Component> type) {
        world.ensureCommandBufferOpen();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        pending.addLast(() -> removeComponentUnchecked(id, type));
        return this;
    }

    /**
     * Returns the number of commands waiting to be applied.
     */
    public int pendingCount() {
        return pending.size();
    }

    void flushInternal() {
        if (flushing) {
            throw new IllegalStateException("Command buffer is already being flushed");
        }
        flushing = true;
        try {
            Runnable command;
            while ((command = pending.pollFirst()) != null) {
                command.run();
            }
        } finally {
            flushing = false;
        }
    }

    void clear() {
        pending.clear();
    }

    private <T extends Component> void removeComponentUnchecked(EntityId id, Class<T> type) {
        world.removeComponent(id, type);
    }
}
