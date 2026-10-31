package cn.managame.data;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * What an entity or log class saves, read once: every instance field that is neither static nor transient, superclass
 * fields first, and the fields marked {@link Id}, {@link GroupKey} and {@link MapKey}. An {@link EntityMapper} builds
 * its statements from it.
 */
public final class EntityType {
    private static final System.Logger LOG = System.getLogger("cn.managame.data");
    private final Class<?> entityClass;
    private final List<EntityField> fields;
    private final EntityField id;
    private final List<EntityField> groupKeys, mapKeys;
    private final MethodHandle constructor;
    /** What a SingleRepository's ids or a GroupRepository's map keys are: the boxed key type, String, or null. */
    final Class<?> keyType;

    private EntityType(Class<?> entityClass) {
        this.entityClass = entityClass;
        if (Modifier.isAbstract(entityClass.getModifiers()) || entityClass.isInterface())
            throw new IllegalArgumentException(entityClass.getName() + " must be a concrete class");
        try {
            constructor = MethodHandles.privateLookupIn(entityClass, MethodHandles.lookup())
                    .findConstructor(entityClass, MethodType.methodType(void.class))
                    .asType(MethodType.methodType(Object.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(entityClass.getName() + " needs a no-argument constructor", e);
        }
        Object newInstance = newInstance();
        List<EntityField> savedFields = new ArrayList<>();
        for (Field field : declaredFields(entityClass)) if (isSaved(field)) savedFields.add(new EntityField(field, newInstance));
        if (savedFields.isEmpty()) throw new IllegalArgumentException(entityClass.getName() + " saves no field");
        fields = List.copyOf(savedFields);
        List<EntityField> ids = fieldsWith(Id.class, null);
        if (ids.size() > 1) throw new IllegalArgumentException(entityClass.getName() + " has more than one @Id");
        id = ids.isEmpty() ? null : ids.getFirst();
        groupKeys = fieldsWith(GroupKey.class, GroupKey::order);
        mapKeys = fieldsWith(MapKey.class, MapKey::order);
        List<EntityField> keys = new ArrayList<>(groupKeys);
        keys.addAll(mapKeys);
        if (id != null) keys.add(id);
        for (EntityField key : keys)
            if (!isKeyType(boxed(key.type())))
                throw new IllegalArgumentException("key field must be integral or String: " + key.field());
        keyType = !mapKeys.isEmpty() ? (mapKeys.size() == 1 ? boxed(mapKeys.getFirst().type()) : String.class)
                : id != null ? boxed(id.type()) : null;
    }

    /**
     * Reads {@code entityClass}. Throws IllegalArgumentException when it is abstract, has no no-argument constructor,
     * saves a final field or none, marks a field that is not saved, has two {@code @Id}s, a key field that is neither
     * integral nor String, or several {@code @GroupKey} or {@code @MapKey} fields without distinct orders.
     */
    public static EntityType of(Class<?> entityClass) { return new EntityType(Objects.requireNonNull(entityClass, "entityClass")); }

    public Class<?> entityClass() { return entityClass; }

    /** The saved fields, superclass fields first. */
    public List<EntityField> fields() { return fields; }

    /** The {@code @Id} field, or null for a log. */
    public EntityField id() { return id; }

    /** The {@code @GroupKey} fields in their order; empty unless the entity is kept in groups. */
    public List<EntityField> groupKeys() { return groupKeys; }

    /** The {@code @MapKey} fields in their order; empty unless the entity is kept in groups. */
    public List<EntityField> mapKeys() { return mapKeys; }

    /** A new instance from the no-argument constructor, as a load fills it. */
    public Object newInstance() {
        try {
            return (Object) constructor.invokeExact();
        } catch (Throwable e) {
            throw new IllegalStateException("cannot create " + entityClass.getName(), e);
        }
    }

    @Override public String toString() { return entityClass.getName(); }

    Object idOf(Object entity) {
        return Objects.requireNonNull(id.get(Objects.requireNonNull(entity, "entity")), "@Id");
    }

    GroupKeys groupKeyOf(Object entity) {
        Object[] values = new Object[groupKeys.size()];
        for (int i = 0; i < values.length; i++) values[i] = Objects.requireNonNull(groupKeys.get(i).get(entity), "@GroupKey");
        return GroupKeys.of(values);
    }

    Object mapKeyOf(Object entity) {
        if (mapKeys.size() == 1) return Objects.requireNonNull(mapKeys.getFirst().get(entity), "@MapKey");
        Object[] values = new Object[mapKeys.size()];
        for (int i = 0; i < values.length; i++) values[i] = mapKeys.get(i).get(entity);
        return MapKeys.of(values);
    }

    /** Throws IllegalArgumentException unless {@code key} has a value of the right type for each GroupKey field. */
    GroupKeys checkGroupKey(GroupKeys key) {
        Objects.requireNonNull(key, "group key");
        if (key.size() != groupKeys.size())
            throw new IllegalArgumentException(entityClass.getSimpleName() + " groups have " + groupKeys.size() + " key values, not " + key.size());
        for (int i = 0; i < key.size(); i++)
            if (key.valueAt(i).getClass() != boxed(groupKeys.get(i).type()))
                throw new IllegalArgumentException("group key value " + i + " of " + entityClass.getSimpleName() + " must be "
                        + boxed(groupKeys.get(i).type()).getSimpleName() + ", not " + key.valueAt(i).getClass().getSimpleName());
        return key;
    }

    /** Throws IllegalArgumentException unless {@code key} is an id or map key of this entity's type. */
    Object checkKeyType(Object key) {
        if (Objects.requireNonNull(key, "key").getClass() != keyType)
            throw new IllegalArgumentException(entityClass.getSimpleName() + " keys are " + keyType.getSimpleName()
                    + ", not " + key.getClass().getSimpleName());
        return key;
    }

    /**
     * Write threads read entities while business threads change them, so a collection a write thread iterates must be
     * thread-safe. A field initialized with a plain java.util collection, or not initialized (a load then fills it with
     * whatever the decoder makes), is named here instead of failing under load.
     */
    void warnNonThreadSafeCollections() {
        Object newInstance = newInstance();
        for (EntityField field : fields) {
            boolean map = Map.class.isAssignableFrom(field.type());
            if (!map && !Collection.class.isAssignableFrom(field.type())) continue;
            Object value = field.get(newInstance);
            if (value != null && isThreadSafe(value.getClass())) continue;
            LOG.log(System.Logger.Level.WARNING, entityClass.getSimpleName() + "." + field.name()
                    + (value == null ? " is not initialized" : " is a " + value.getClass().getName())
                    + ", which a write thread may read while it is changed; initialize it with a thread-safe collection such as "
                    + (map ? "ConcurrentHashMap" : "CopyOnWriteArrayList"));
        }
    }

    /** The boxed class of a primitive, or the class itself. */
    public static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == long.class) return Long.class;
        if (type == int.class) return Integer.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        return Character.class;
    }

    private static boolean isKeyType(Class<?> type) {
        return type == Long.class || type == Integer.class || type == Short.class || type == Byte.class || type == String.class;
    }

    private static List<Field> declaredFields(Class<?> type) {
        List<Class<?>> classes = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) classes.addFirst(c);
        List<Field> fields = new ArrayList<>();
        for (Class<?> c : classes) fields.addAll(Arrays.asList(c.getDeclaredFields()));
        return fields;
    }

    /** A final field could not be set again on load, so it is refused rather than quietly left unsaved. */
    private static boolean isSaved(Field field) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) return false;
        if (Modifier.isFinal(modifiers))
            throw new IllegalArgumentException("saved field must not be final: " + field + " (make it transient if it is not saved)");
        return true;
    }

    private <A extends Annotation> List<EntityField> fieldsWith(Class<A> annotation, ToIntFunction<A> orderOf) {
        List<EntityField> annotated = fields.stream().filter(field -> field.field().isAnnotationPresent(annotation)).toList();
        for (Field field : declaredFields(entityClass))
            if (field.isAnnotationPresent(annotation) && annotated.stream().noneMatch(saved -> saved.field().equals(field)))
                throw new IllegalArgumentException("@" + annotation.getSimpleName() + " field must be saved: " + field);
        if (orderOf == null || annotated.size() < 2) return annotated;
        Set<Integer> orders = new HashSet<>();
        for (EntityField field : annotated) {
            int order = orderOf.applyAsInt(field.field().getAnnotation(annotation));
            if (order == Integer.MIN_VALUE || !orders.add(order))
                throw new IllegalArgumentException("several @" + annotation.getSimpleName() + " fields need distinct orders: " + field.field());
        }
        return annotated.stream().sorted(Comparator.comparingInt(field -> orderOf.applyAsInt(field.field().getAnnotation(annotation)))).toList();
    }

    private static boolean isThreadSafe(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.util.concurrent.") || name.startsWith("java.util.Collections$Synchronized")
                || name.startsWith("java.util.Collections$Unmodifiable") || name.startsWith("java.util.ImmutableCollections$");
    }
}
