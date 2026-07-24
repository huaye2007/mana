package cn.managame.ecs.change;

import cn.managame.ecs.Component;
import cn.managame.ecs.EntityId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One client-observable change in an ECS world.
 *
 * <p>The component payloads are component references, not serialized bytes.
 * A sink should serialize or copy them while handling its batch.</p>
 */
public sealed interface WorldChange permits
        WorldChange.EntityCreated,
        WorldChange.EntityDestroyed,
        WorldChange.ComponentChanged {

    /**
     * Returns the entity affected by this change.
     */
    EntityId entityId();

    /**
     * Signals that an entity now exists.
     *
     * @param entityId created entity
     */
    record EntityCreated(EntityId entityId) implements WorldChange {

        public EntityCreated {
            Objects.requireNonNull(entityId, "entityId");
        }
    }

    /**
     * Signals that an entity and all of its components no longer exist.
     *
     * @param entityId destroyed entity
     */
    record EntityDestroyed(EntityId entityId) implements WorldChange {

        public EntityDestroyed {
            Objects.requireNonNull(entityId, "entityId");
        }
    }

    /**
     * Describes an added, replaced, removed, or field-updated component.
     *
     * @param entityId affected entity
     * @param componentType exact component storage key
     * @param kind component change kind
     * @param previousComponent component before replacement or removal, otherwise {@code null}
     * @param currentComponent component after addition, replacement, or field update, otherwise {@code null}
     * @param fields field deltas for {@link ComponentChangeKind#FIELDS_UPDATED}
     */
    record ComponentChanged(
            EntityId entityId,
            Class<? extends Component> componentType,
            ComponentChangeKind kind,
            Component previousComponent,
            Component currentComponent,
            Map<String, FieldChange> fields) implements WorldChange {

        public ComponentChanged {
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(componentType, "componentType");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(fields, "fields");
            fields = immutableFields(fields);
            requireCompatible(componentType, previousComponent, "previousComponent");
            requireCompatible(componentType, currentComponent, "currentComponent");

            switch (kind) {
                case ADDED -> {
                    requireNull(previousComponent, "previousComponent", kind);
                    requirePresent(currentComponent, "currentComponent", kind);
                    requireNoFields(fields, kind);
                }
                case REPLACED -> {
                    requirePresent(previousComponent, "previousComponent", kind);
                    requirePresent(currentComponent, "currentComponent", kind);
                    requireNoFields(fields, kind);
                }
                case REMOVED -> {
                    requirePresent(previousComponent, "previousComponent", kind);
                    requireNull(currentComponent, "currentComponent", kind);
                    requireNoFields(fields, kind);
                }
                case FIELDS_UPDATED -> {
                    requireNull(previousComponent, "previousComponent", kind);
                    requirePresent(currentComponent, "currentComponent", kind);
                    if (fields.isEmpty()) {
                        throw new IllegalArgumentException(
                                "FIELDS_UPDATED requires at least one field");
                    }
                }
            }
        }

        /**
         * Creates a component-added change.
         */
        public static ComponentChanged added(
                EntityId entityId,
                Class<? extends Component> componentType,
                Component component) {
            return new ComponentChanged(
                    entityId, componentType, ComponentChangeKind.ADDED,
                    null, component, Map.of());
        }

        /**
         * Creates a component-replaced change.
         */
        public static ComponentChanged replaced(
                EntityId entityId,
                Class<? extends Component> componentType,
                Component previous,
                Component current) {
            return new ComponentChanged(
                    entityId, componentType, ComponentChangeKind.REPLACED,
                    previous, current, Map.of());
        }

        /**
         * Creates a component-removed change.
         */
        public static ComponentChanged removed(
                EntityId entityId,
                Class<? extends Component> componentType,
                Component previous) {
            return new ComponentChanged(
                    entityId, componentType, ComponentChangeKind.REMOVED,
                    previous, null, Map.of());
        }

        /**
         * Creates a component field-update change.
         */
        public static ComponentChanged fieldsUpdated(
                EntityId entityId,
                Class<? extends Component> componentType,
                Component current,
                Map<String, FieldChange> fields) {
            return new ComponentChanged(
                    entityId, componentType, ComponentChangeKind.FIELDS_UPDATED,
                    null, current, fields);
        }

        private static Map<String, FieldChange> immutableFields(
                Map<String, FieldChange> fields) {
            LinkedHashMap<String, FieldChange> copy = new LinkedHashMap<>();
            fields.forEach((name, change) -> {
                Objects.requireNonNull(name, "field name");
                Objects.requireNonNull(change, "field change");
                if (!name.equals(change.fieldName())) {
                    throw new IllegalArgumentException(
                            "Field map key does not match FieldChange: " + name);
                }
                copy.put(name, change);
            });
            return Collections.unmodifiableMap(copy);
        }

        private static void requirePresent(
                Object value, String field, ComponentChangeKind kind) {
            if (value == null) {
                throw new IllegalArgumentException(field + " is required for " + kind);
            }
        }

        private static void requireNull(
                Object value, String field, ComponentChangeKind kind) {
            if (value != null) {
                throw new IllegalArgumentException(field + " must be null for " + kind);
            }
        }

        private static void requireNoFields(
                Map<String, FieldChange> fields, ComponentChangeKind kind) {
            if (!fields.isEmpty()) {
                throw new IllegalArgumentException("Field deltas are not valid for " + kind);
            }
        }

        private static void requireCompatible(
                Class<? extends Component> componentType,
                Component component,
                String field) {
            if (component != null && !componentType.isInstance(component)) {
                throw new IllegalArgumentException(
                        field + " is not an instance of " + componentType.getName());
            }
        }
    }
}
