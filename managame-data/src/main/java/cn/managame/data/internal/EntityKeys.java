package cn.managame.data.internal;

import cn.managame.data.GroupKeys;
import cn.managame.data.MapKeys;
import cn.managame.data.spi.EntityField;
import cn.managame.data.spi.EntityType;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * An entity type's keys as its repository uses them: read from an entity, and checked when the application passes
 * them. Not API: it may change in any release.
 */
public final class EntityKeys {
    private static final System.Logger LOG = System.getLogger("cn.managame.data");
    private final EntityType type;
    private final Class<?> keyType;

    public EntityKeys(EntityType type) {
        this.type = type;
        keyType = !type.mapKeys().isEmpty() ? (type.mapKeys().size() == 1 ? EntityType.boxed(type.mapKeys().getFirst().type()) : String.class)
                : type.id() != null ? EntityType.boxed(type.id().type()) : null;
    }

    /** What a SingleRepository's ids or a GroupRepository's map keys are: the boxed key type, String, or null. */
    public Class<?> keyType() { return keyType; }

    public Object idOf(Object entity) {
        return Objects.requireNonNull(type.id().get(Objects.requireNonNull(entity, "entity")), "@Id");
    }

    public GroupKeys groupKeyOf(Object entity) {
        Object[] values = new Object[type.groupKeys().size()];
        for (int i = 0; i < values.length; i++) values[i] = Objects.requireNonNull(type.groupKeys().get(i).get(entity), "@GroupKey");
        return GroupKeys.of(values);
    }

    public Object mapKeyOf(Object entity) {
        if (type.mapKeys().size() == 1) return Objects.requireNonNull(type.mapKeys().getFirst().get(entity), "@MapKey");
        Object[] values = new Object[type.mapKeys().size()];
        for (int i = 0; i < values.length; i++) values[i] = type.mapKeys().get(i).get(entity);
        return MapKeys.of(values);
    }

    /** Throws IllegalArgumentException unless {@code key} has a value of the right type for each GroupKey field. */
    public GroupKeys checkGroupKey(GroupKeys key) {
        Objects.requireNonNull(key, "group key");
        if (key.size() != type.groupKeys().size())
            throw new IllegalArgumentException(name() + " groups have " + type.groupKeys().size() + " key values, not " + key.size());
        for (int i = 0; i < key.size(); i++) {
            Class<?> expected = EntityType.boxed(type.groupKeys().get(i).type());
            if (key.valueAt(i).getClass() != expected)
                throw new IllegalArgumentException("group key value " + i + " of " + name() + " must be " + expected.getSimpleName()
                        + ", not " + key.valueAt(i).getClass().getSimpleName());
        }
        return key;
    }

    /** Throws IllegalArgumentException unless {@code key} is an id or map key of this entity's type. */
    public Object checkKeyType(Object key) {
        if (Objects.requireNonNull(key, "key").getClass() != keyType)
            throw new IllegalArgumentException(name() + " keys are " + keyType.getSimpleName() + ", not " + key.getClass().getSimpleName());
        return key;
    }

    /**
     * Write threads read entities while business threads change them, so a collection a write thread iterates must be
     * thread-safe. A field initialized with a plain java.util collection, or not initialized (a load then fills it with
     * whatever the decoder makes), is named here instead of failing under load.
     */
    public void warnNonThreadSafeCollections() {
        Object newInstance = type.newInstance();
        for (EntityField field : type.fields()) {
            boolean map = Map.class.isAssignableFrom(field.type());
            if (!map && !Collection.class.isAssignableFrom(field.type())) continue;
            Object value = field.get(newInstance);
            if (value != null && isThreadSafe(value.getClass())) continue;
            LOG.log(System.Logger.Level.WARNING, name() + "." + field.name()
                    + (value == null ? " is not initialized" : " is a " + value.getClass().getName())
                    + ", which a write thread may read while it is changed; initialize it with a thread-safe collection such as "
                    + (map ? "ConcurrentHashMap" : "CopyOnWriteArrayList"));
        }
    }

    private String name() { return type.entityClass().getSimpleName(); }

    private static boolean isThreadSafe(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.util.concurrent.") || name.startsWith("java.util.Collections$Synchronized")
                || name.startsWith("java.util.Collections$Unmodifiable") || name.startsWith("java.util.ImmutableCollections$");
    }
}
