package cn.managame.data.meta;

import cn.managame.data.annotation.Id;
import cn.managame.data.annotation.MapKey;
import cn.managame.data.key.GroupKey;
import cn.managame.data.key.MapKeys;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;

/** Identity metadata shared by storage adapters; contains no SQL or BSON mapping. */
public final class EntityMeta {
    public enum Kind { SINGLE, GROUP }
    private final Class<?> type;
    private final Kind kind;
    private final MethodHandle constructor;
    private final List<Field> fields;
    private final FieldAccessor id;
    private final List<FieldAccessor> groupKeys;
    private final List<FieldAccessor> mapKeys;

    public EntityMeta(Class<?> type, Kind kind) {
        this.type = type;
        this.kind = kind;
        fields = fieldsOf(type);
        constructor = constructorOf(type);
        var ids = fields.stream().filter(f -> f.isAnnotationPresent(Id.class)).toList();
        if (ids.size() != 1) throw new IllegalArgumentException(type + " requires exactly one @Id");
        id = new FieldAccessor(ids.getFirst());
        groupKeys = ordered(fields, true);
        mapKeys = ordered(fields, false);
        if (kind == Kind.SINGLE && (!groupKeys.isEmpty() || !mapKeys.isEmpty()))
            throw new IllegalArgumentException("Single entity cannot have GroupKey/MapKey");
        if (kind == Kind.GROUP && (groupKeys.isEmpty() || mapKeys.isEmpty()))
            throw new IllegalArgumentException("Group entity requires GroupKey and MapKey");
        for (FieldAccessor a : identityFields()) {
            Class<?> t = boxed(a.type());
            if (!(t == String.class || t == Long.class || t == Integer.class || t == Short.class || t == Byte.class))
                throw new IllegalArgumentException("Identity field must be integral or String: " + a.field());
        }
    }
    public static List<Field> fieldsOf(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != Object.class && c != null; c = c.getSuperclass())
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
        return List.copyOf(fields);
    }
    public static MethodHandle constructorOf(Class<?> type) {
        if (Modifier.isAbstract(type.getModifiers())) throw new IllegalArgumentException("Abstract entity/repository: " + type);
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                    .findConstructor(type, MethodType.methodType(void.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("No accessible no-arg constructor: " + type, e);
        }
    }
    public static Object construct(MethodHandle constructor) {
        try { return constructor.invoke(); }
        catch (Throwable e) { throw new IllegalArgumentException("Object construction failed", e); }
    }
    private static List<FieldAccessor> ordered(List<Field> fields, boolean group) {
        var selected = fields.stream().filter(f -> group
                ? f.isAnnotationPresent(cn.managame.data.annotation.GroupKey.class)
                : f.isAnnotationPresent(MapKey.class)).toList();
        java.util.function.ToIntFunction<Field> order = f -> group
                ? f.getAnnotation(cn.managame.data.annotation.GroupKey.class).order()
                : f.getAnnotation(MapKey.class).order();
        if (selected.size() > 1) {
            Set<Integer> orders = new HashSet<>();
            for (Field f : selected) {
                int n = order.applyAsInt(f);
                if (n == Integer.MIN_VALUE || !orders.add(n))
                    throw new IllegalArgumentException("Composite key needs distinct explicit orders: " + f);
            }
        }
        return selected.stream().sorted(Comparator.comparingInt(order)).map(FieldAccessor::new).toList();
    }
    public Class<?> entityType() { return type; }
    public Kind kind() { return kind; }
    public List<Field> fields() { return fields; }
    public FieldAccessor idField() { return id; }
    public List<FieldAccessor> groupFields() { return groupKeys; }
    public List<FieldAccessor> mapFields() { return mapKeys; }
    public List<FieldAccessor> identityFields() {
        List<FieldAccessor> all = new ArrayList<>(); all.add(id); all.addAll(groupKeys); all.addAll(mapKeys); return all;
    }
    public Object create() { return construct(constructor); }
    public Object getId(Object entity) { return Objects.requireNonNull(id.get(entity), "Null @Id"); }
    public GroupKey getGroupKey(Object entity) {
        return GroupKey.of(groupKeys.stream().map(a -> Objects.requireNonNull(a.get(entity), "Null GroupKey")).toArray());
    }
    public Object getMapKey(Object entity) {
        if (mapKeys.size() == 1) return Objects.requireNonNull(mapKeys.getFirst().get(entity), "Null MapKey");
        return MapKeys.of(mapKeys.stream().map(a -> a.get(entity)).toArray());
    }
    public void validateGroupKey(GroupKey key) {
        if (key.size() != groupKeys.size()) throw new IllegalArgumentException("GroupKey arity mismatch");
        for (int i = 0; i < key.size(); i++)
            if (key.valueAt(i).getClass() != boxed(groupKeys.get(i).type()))
                throw new IllegalArgumentException("GroupKey type mismatch at " + i);
    }
    public Class<?> keyType() { return kind == Kind.SINGLE ? boxed(id.type())
            : mapKeys.size() == 1 ? boxed(mapKeys.getFirst().type()) : String.class; }
    public static Class<?> boxed(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == long.class) return Long.class; if (c == int.class) return Integer.class;
        if (c == short.class) return Short.class; if (c == byte.class) return Byte.class;
        if (c == boolean.class) return Boolean.class; if (c == char.class) return Character.class;
        if (c == float.class) return Float.class; if (c == double.class) return Double.class;
        return c;
    }
}
