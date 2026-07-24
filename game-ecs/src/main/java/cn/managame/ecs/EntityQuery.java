package cn.managame.ecs;

import java.util.Iterator;
import java.util.List;

/**
 * Reusable query that selects entities containing all required component types.
 *
 * <p>Each iteration uses a fresh snapshot. Structural changes made after an
 * iterator is created are visible only to the next iteration.</p>
 */
public final class EntityQuery implements Iterable<Entity> {

    private final EcsWorld world;
    private final List<Class<? extends Component>> requiredTypes;

    EntityQuery(EcsWorld world, List<Class<? extends Component>> requiredTypes) {
        this.world = world;
        this.requiredTypes = requiredTypes;
    }

    /**
     * Returns the required component types in declaration order.
     */
    public List<Class<? extends Component>> requiredTypes() {
        return requiredTypes;
    }

    /**
     * Materializes the current matching entities as an immutable snapshot.
     */
    public List<Entity> entities() {
        return world.querySnapshot(requiredTypes);
    }

    /**
     * Returns the current number of matching entities.
     */
    public int count() {
        return world.queryCount(requiredTypes);
    }

    /**
     * Returns whether no current entity matches this query.
     */
    public boolean isEmpty() {
        return count() == 0;
    }

    @Override
    public Iterator<Entity> iterator() {
        return entities().iterator();
    }
}
