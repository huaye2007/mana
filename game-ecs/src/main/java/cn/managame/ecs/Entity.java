package cn.managame.ecs;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Handle used to access one entity in an {@link EcsWorld}.
 *
 * <p>A handle remains comparable after its entity is destroyed, but component
 * access on a destroyed entity fails with {@link IllegalStateException}.</p>
 */
public final class Entity {

    private final EcsWorld world;
    private final EntityId id;

    Entity(EcsWorld world, EntityId id) {
        this.world = world;
        this.id = id;
    }

    /**
     * Returns this entity's stable identifier.
     */
    public EntityId id() {
        return id;
    }

    /**
     * Returns whether this entity still exists in its world.
     */
    public boolean isAlive() {
        return world.isAlive(id);
    }

    /**
     * Adds or replaces a component using its concrete runtime class as the key.
     */
    public Entity add(Component component) {
        world.putInferredComponent(id, Objects.requireNonNull(component, "component"));
        return this;
    }

    /**
     * Adds or replaces a component under an explicit component type.
     *
     * <p>This overload is useful when components are retrieved through a shared
     * interface or base class.</p>
     */
    public <T extends Component> Entity add(Class<T> type, T component) {
        world.putComponent(id, type, component);
        return this;
    }

    /**
     * Finds a component stored under the exact requested type.
     */
    public <T extends Component> Optional<T> find(Class<T> type) {
        return world.findComponent(id, type);
    }

    /**
     * Returns a component stored under the exact requested type.
     *
     * @throws IllegalStateException if the entity does not contain the component
     */
    public <T extends Component> T require(Class<T> type) {
        return find(type).orElseThrow(() -> new IllegalStateException(
                "Entity " + id + " does not contain component " + type.getName()));
    }

    /**
     * Returns whether a component is stored under the exact requested type.
     */
    public boolean has(Class<? extends Component> type) {
        return world.hasComponent(id, type);
    }

    /**
     * Removes and returns a component stored under the exact requested type.
     */
    public <T extends Component> Optional<T> remove(Class<T> type) {
        return world.removeComponent(id, type);
    }

    /**
     * Updates one mutable business field and records its effective old and new
     * values when change tracking is enabled.
     *
     * <p>The getter is invoked before and after the setter, so normalization
     * performed by the component is reflected in the emitted change. No change
     * is recorded when both effective values are equal.</p>
     */
    public <T extends Component, V> Entity updateField(
            Class<T> componentType,
            String fieldName,
            Function<? super T, ? extends V> getter,
            BiConsumer<? super T, ? super V> setter,
            V newValue) {
        world.updateField(id, componentType, fieldName, getter, setter, newValue);
        return this;
    }

    /**
     * Manually records a field delta after an in-place mutation.
     *
     * <p>This is intended for mutable collections or domain operations that
     * cannot be expressed with {@link #updateField}. Pass immutable old and new
     * snapshots when the values themselves are mutable.</p>
     */
    public Entity markChanged(
            Class<? extends Component> componentType,
            String fieldName,
            Object previousValue,
            Object currentValue) {
        world.markFieldChanged(
                id, componentType, fieldName, previousValue, currentValue);
        return this;
    }

    /**
     * Returns an immutable snapshot of the component types on this entity.
     */
    public Set<Class<? extends Component>> componentTypes() {
        return world.componentTypes(id);
    }

    /**
     * Removes this entity and all its components from the world.
     */
    public boolean destroy() {
        return world.destroyEntity(id);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Entity entity
                && world == entity.world
                && id.equals(entity.id);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(world) + id.hashCode();
    }

    @Override
    public String toString() {
        return "Entity[id=" + id + ", alive=" + isAlive() + ']';
    }
}
