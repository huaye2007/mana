package cn.managame.ecs;

import cn.managame.ecs.change.ComponentChangeKind;
import cn.managame.ecs.change.FieldChange;
import cn.managame.ecs.change.WorldChange;
import cn.managame.ecs.change.WorldChangeBatch;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Mutable, owner-thread-only journal used internally by {@link EcsWorld}.
 */
final class WorldChangeJournal {

    private final List<WorldChange> pending = new ArrayList<>();
    private long batchSequence;

    void entityCreated(EntityId entityId) {
        pending.add(new WorldChange.EntityCreated(entityId));
    }

    void entityDestroyed(EntityId entityId) {
        pending.add(new WorldChange.EntityDestroyed(entityId));
    }

    void componentAdded(
            EntityId entityId,
            Class<? extends Component> componentType,
            Component component) {
        pending.add(WorldChange.ComponentChanged.added(
                entityId, componentType, component));
    }

    void componentReplaced(
            EntityId entityId,
            Class<? extends Component> componentType,
            Component previous,
            Component current) {
        pending.add(WorldChange.ComponentChanged.replaced(
                entityId, componentType, previous, current));
    }

    void componentRemoved(
            EntityId entityId,
            Class<? extends Component> componentType,
            Component previous) {
        pending.add(WorldChange.ComponentChanged.removed(
                entityId, componentType, previous));
    }

    void fieldUpdated(
            EntityId entityId,
            Class<? extends Component> componentType,
            Component component,
            FieldChange field) {
        pending.add(WorldChange.ComponentChanged.fieldsUpdated(
                entityId, componentType, component,
                Map.of(field.fieldName(), field)));
    }

    int pendingCount() {
        return pending.size();
    }

    Optional<WorldChangeBatch> drain() {
        if (pending.isEmpty()) {
            return Optional.empty();
        }

        List<WorldChange> changes = coalesce(pending);
        pending.clear();
        if (changes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new WorldChangeBatch(++batchSequence, changes));
    }

    void clear() {
        pending.clear();
    }

    private static List<WorldChange> coalesce(List<WorldChange> source) {
        LinkedHashMap<Object, WorldChange> merged = new LinkedHashMap<>();
        for (WorldChange change : source) {
            switch (change) {
                case WorldChange.EntityCreated created ->
                        merged.put(new EntityKey(created.entityId()), created);
                case WorldChange.EntityDestroyed destroyed ->
                        mergeEntityDestroyed(merged, destroyed);
                case WorldChange.ComponentChanged component ->
                        mergeComponentChanged(merged, component);
            }
        }
        return List.copyOf(merged.values());
    }

    private static void mergeEntityDestroyed(
            LinkedHashMap<Object, WorldChange> merged,
            WorldChange.EntityDestroyed destroyed) {
        EntityKey entityKey = new EntityKey(destroyed.entityId());
        boolean createdInBatch =
                merged.remove(entityKey) instanceof WorldChange.EntityCreated;

        Iterator<Object> keys = merged.keySet().iterator();
        while (keys.hasNext()) {
            Object key = keys.next();
            if (key instanceof ComponentKey componentKey
                    && componentKey.entityId.equals(destroyed.entityId())) {
                keys.remove();
            }
        }

        if (!createdInBatch) {
            merged.put(entityKey, destroyed);
        }
    }

    private static void mergeComponentChanged(
            LinkedHashMap<Object, WorldChange> merged,
            WorldChange.ComponentChanged incoming) {
        if (merged.get(new EntityKey(incoming.entityId()))
                instanceof WorldChange.EntityDestroyed) {
            return;
        }

        ComponentKey key =
                new ComponentKey(incoming.entityId(), incoming.componentType());
        WorldChange.ComponentChanged current =
                (WorldChange.ComponentChanged) merged.get(key);
        WorldChange.ComponentChanged result = mergeComponent(current, incoming);
        if (result == null) {
            merged.remove(key);
        } else {
            merged.put(key, result);
        }
    }

    private static WorldChange.ComponentChanged mergeComponent(
            WorldChange.ComponentChanged current,
            WorldChange.ComponentChanged incoming) {
        if (current == null) {
            return incoming;
        }

        return switch (incoming.kind()) {
            case ADDED -> mergeAdded(current, incoming);
            case REPLACED -> mergeReplaced(current, incoming);
            case REMOVED -> mergeRemoved(current, incoming);
            case FIELDS_UPDATED -> mergeFields(current, incoming);
        };
    }

    private static WorldChange.ComponentChanged mergeAdded(
            WorldChange.ComponentChanged current,
            WorldChange.ComponentChanged incoming) {
        if (current.kind() == ComponentChangeKind.REMOVED) {
            if (valuesEqual(current.previousComponent(), incoming.currentComponent())) {
                return null;
            }
            return WorldChange.ComponentChanged.replaced(
                    incoming.entityId(),
                    incoming.componentType(),
                    current.previousComponent(),
                    incoming.currentComponent());
        }
        if (current.kind() == ComponentChangeKind.ADDED) {
            return WorldChange.ComponentChanged.added(
                    incoming.entityId(),
                    incoming.componentType(),
                    incoming.currentComponent());
        }
        return incoming;
    }

    private static WorldChange.ComponentChanged mergeReplaced(
            WorldChange.ComponentChanged current,
            WorldChange.ComponentChanged incoming) {
        return switch (current.kind()) {
            case ADDED -> WorldChange.ComponentChanged.added(
                    incoming.entityId(),
                    incoming.componentType(),
                    incoming.currentComponent());
            case REPLACED -> WorldChange.ComponentChanged.replaced(
                    incoming.entityId(),
                    incoming.componentType(),
                    current.previousComponent(),
                    incoming.currentComponent());
            case REMOVED -> WorldChange.ComponentChanged.replaced(
                    incoming.entityId(),
                    incoming.componentType(),
                    current.previousComponent(),
                    incoming.currentComponent());
            case FIELDS_UPDATED -> incoming;
        };
    }

    private static WorldChange.ComponentChanged mergeRemoved(
            WorldChange.ComponentChanged current,
            WorldChange.ComponentChanged incoming) {
        return switch (current.kind()) {
            case ADDED -> null;
            case REPLACED -> WorldChange.ComponentChanged.removed(
                    incoming.entityId(),
                    incoming.componentType(),
                    current.previousComponent());
            case REMOVED -> current;
            case FIELDS_UPDATED -> incoming;
        };
    }

    private static WorldChange.ComponentChanged mergeFields(
            WorldChange.ComponentChanged current,
            WorldChange.ComponentChanged incoming) {
        return switch (current.kind()) {
            case ADDED, REPLACED -> current;
            case REMOVED -> throw new IllegalStateException(
                    "Cannot update fields of a removed component");
            case FIELDS_UPDATED -> mergeFieldMaps(current, incoming);
        };
    }

    private static WorldChange.ComponentChanged mergeFieldMaps(
            WorldChange.ComponentChanged current,
            WorldChange.ComponentChanged incoming) {
        LinkedHashMap<String, FieldChange> fields =
                new LinkedHashMap<>(current.fields());
        incoming.fields().forEach((name, next) -> {
            FieldChange first = fields.get(name);
            if (first == null) {
                fields.put(name, next);
                return;
            }

            FieldChange combined = new FieldChange(
                    name, first.previousValue(), next.currentValue());
            if (valuesEqual(combined.previousValue(), combined.currentValue())) {
                fields.remove(name);
            } else {
                fields.put(name, combined);
            }
        });

        if (fields.isEmpty()) {
            return null;
        }
        return WorldChange.ComponentChanged.fieldsUpdated(
                incoming.entityId(),
                incoming.componentType(),
                incoming.currentComponent(),
                fields);
    }

    private static boolean valuesEqual(Object left, Object right) {
        return Objects.deepEquals(left, right);
    }

    private record EntityKey(EntityId entityId) {
    }

    private record ComponentKey(
            EntityId entityId,
            Class<? extends Component> componentType) {
    }
}
