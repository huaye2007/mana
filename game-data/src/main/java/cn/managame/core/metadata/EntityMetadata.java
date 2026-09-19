package cn.managame.core.metadata;

import cn.managame.core.DataException;
import cn.managame.core.access.Query;
import cn.managame.core.cache.CacheMode;
import cn.managame.core.key.GroupKeys;
import cn.managame.core.key.MapKeys;
import cn.managame.core.repository.GroupRepository;

import cn.managame.annotation.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.*;

public final class EntityMetadata<T> {
    private final Class<T> type;
    private final String rdbTable;
    private final String rdbSchema;
    private final String docCollection;
    private final Constructor<T> constructor;
    private final List<PropertyMetadata> properties;
    private final Map<String, PropertyMetadata> propertiesByName;
    private final PropertyMetadata idProperty;
    private final List<PropertyMetadata> groupKeyProperties;
    private final Class<?> groupKeyType;
    private final List<PropertyMetadata> mapKeyProperties;
    private final List<IndexMetadata> indexes;
    private final List<CompoundIndex> compoundIndexes;
    private final CacheMode cacheMode;

    private EntityMetadata(
            Class<T> type,
            String rdbTable,
            String rdbSchema,
            String docCollection,
            Constructor<T> constructor,
            List<PropertyMetadata> properties,
            PropertyMetadata idProperty,
            List<PropertyMetadata> groupKeyProperties,
            List<PropertyMetadata> mapKeyProperties,
            List<IndexMetadata> indexes,
            List<CompoundIndex> compoundIndexes,
            CacheMode cacheMode) {
        this.type = type;
        this.rdbTable = rdbTable;
        this.rdbSchema = rdbSchema;
        this.docCollection = docCollection;
        this.constructor = constructor;
        this.properties = List.copyOf(properties);
        Map<String, PropertyMetadata> byName = new HashMap<>();
        for (PropertyMetadata p : properties) byName.put(p.propertyName(), p);
        this.propertiesByName = Map.copyOf(byName);
        this.idProperty = idProperty;
        this.groupKeyProperties = List.copyOf(groupKeyProperties);
        this.groupKeyType = groupKeyProperties.size() == 1
                ? java.lang.invoke.MethodType.methodType(groupKeyProperties.getFirst().type()).wrap().returnType()
                : String.class;
        this.mapKeyProperties = List.copyOf(mapKeyProperties);
        this.indexes = List.copyOf(indexes);
        this.compoundIndexes = List.copyOf(compoundIndexes);
        this.cacheMode = Objects.requireNonNull(cacheMode);
    }

    public static <T> EntityMetadata<T> inspect(Class<T> type) {
        Table table = type.getAnnotation(Table.class);
        Document document = type.getAnnotation(Document.class);

        String defaultName = Naming.snakeCase(type.getSimpleName());
        String rdbTable = table != null && !table.value().isBlank() ? table.value() : defaultName;
        String rdbSchema = table != null ? table.schema() : "";
        String docCollection;
        if (document != null && !document.value().isBlank()) docCollection = document.value();
        else if (table != null && !table.value().isBlank()) docCollection = table.value();
        else docCollection = defaultName;

        Constructor<T> constructor;
        try {
            constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new DataException(type.getName() + " must declare a no-arg constructor", e);
        }

        List<PropertyMetadata> properties = new ArrayList<>();
        List<IndexMetadata> indexes = new ArrayList<>();
        List<GroupProperty> groupProperties = new ArrayList<>();
        List<MapProperty> mapProperties = new ArrayList<>();
        PropertyMetadata id = null;

        for (java.lang.reflect.Field f : allFields(type)) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())
                    || f.isAnnotationPresent(Transient.class)) continue;

            boolean isId = f.isAnnotationPresent(Id.class);
            Column column = f.getAnnotation(Column.class);
            cn.managame.annotation.Field docField = f.getAnnotation(cn.managame.annotation.Field.class);

            String defaultField = Naming.snakeCase(f.getName());
            String rdbName = column != null && !column.value().isBlank() ? column.value() : defaultField;
            String docName;
            if (isId) docName = "_id";
            else if (docField != null && !docField.value().isBlank()) docName = docField.value();
            else if (column != null && !column.value().isBlank()) docName = column.value();
            else docName = defaultField;

            cn.managame.annotation.ColumnType columnType = column == null
                    ? cn.managame.annotation.ColumnType.AUTO : column.type();
            int columnLength = column == null ? 255 : column.length();
            PropertyMetadata property = new PropertyMetadata(
                    f, f.getName(), rdbName, docName, isId, columnType, columnLength);
            properties.add(property);

            if (isId) {
                if (id != null) throw new DataException("Only one @Id is supported: " + type.getName());
                id = property;
            }

            cn.managame.annotation.GroupKey groupKey = f.getAnnotation(cn.managame.annotation.GroupKey.class);
            if (groupKey != null) groupProperties.add(new GroupProperty(groupKey.order(), property));

            cn.managame.annotation.MapKey mapKey = f.getAnnotation(cn.managame.annotation.MapKey.class);
            if (mapKey != null) mapProperties.add(new MapProperty(mapKey.order(), property));

            Indexed indexed = f.getAnnotation(Indexed.class);
            if (indexed != null) {
                String indexName = indexed.name().isBlank()
                        ? "idx_" + rdbTable + "_" + rdbName
                        : indexed.name();
                indexes.add(new IndexMetadata(indexName, f.getName(), rdbName, indexed.unique(),
                        indexed.sparse(), indexed.direction()));
            }
        }

        if (id == null) throw new DataException(type.getName() + " must declare one @Id field");
        if (table != null) {
            for (cn.managame.annotation.Index index : table.indexes()) {
                List<IndexMetadata.Column> columns = new ArrayList<>();
                Set<String> used = new HashSet<>();
                for (String part : index.columnList().split(",", -1)) {
                    String[] tokens = part.trim().split("\\s+");
                    if (tokens.length < 1 || tokens.length > 2 || tokens[0].isBlank()) {
                        throw new DataException("Invalid index columnList: " + index.columnList());
                    }
                    PropertyMetadata property = properties.stream().filter(p -> p.rdbName().equals(tokens[0])).findFirst()
                            .orElseThrow(() -> new DataException("Unknown index column: " + tokens[0] + " on " + type.getName()));
                    if (!used.add(property.rdbName())) throw new DataException("Repeated index column: " + property.rdbName());
                    IndexDirection direction;
                    try { direction = tokens.length == 1 ? IndexDirection.ASC : IndexDirection.valueOf(tokens[1].toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException error) { throw new DataException("Invalid index direction: " + part, error); }
                    columns.add(new IndexMetadata.Column(property.propertyName(), property.rdbName(), direction));
                }
                String name = index.name().isBlank() ? "idx_" + rdbTable + "_"
                        + String.join("_", columns.stream().map(IndexMetadata.Column::storeName).toList()) : index.name();
                indexes.add(new IndexMetadata(name, columns, index.unique(), false));
            }
        }
        Set<String> indexNames = new HashSet<>();
        for (IndexMetadata index : indexes) {
            if (!indexNames.add(index.name().toLowerCase(Locale.ROOT))) throw new DataException("Duplicate index name: " + index.name());
        }


        groupProperties.sort(Comparator.comparingInt(GroupProperty::order));
        for (int i = 1; i < groupProperties.size(); i++) {
            if (groupProperties.get(i - 1).order() == groupProperties.get(i).order()) {
                throw new DataException("Duplicate @GroupKey order=" + groupProperties.get(i).order()
                        + " on " + type.getName());
            }
        }
        List<PropertyMetadata> groupKeyProperties = groupProperties.stream()
                .map(GroupProperty::property)
                .toList();

        mapProperties.sort(Comparator.comparingInt(MapProperty::order));
        for (int i = 1; i < mapProperties.size(); i++) {
            if (mapProperties.get(i - 1).order() == mapProperties.get(i).order()) {
                throw new DataException("Duplicate @MapKey order=" + mapProperties.get(i).order()
                        + " on " + type.getName());
            }
        }
        List<PropertyMetadata> mapKeyProperties = mapProperties.stream()
                .map(MapProperty::property)
                .toList();

        CacheMode cacheMode = type.isAnnotationPresent(Resident.class) ? CacheMode.RESIDENT : CacheMode.LAZY;

        return new EntityMetadata<>(
                type, rdbTable, rdbSchema, docCollection, constructor,
                properties, id, groupKeyProperties, mapKeyProperties, indexes,
                Arrays.asList(type.getAnnotationsByType(CompoundIndex.class)), cacheMode);
    }

    private record GroupProperty(int order, PropertyMetadata property) {}
    private record MapProperty(int order, PropertyMetadata property) {}

    private static List<java.lang.reflect.Field> allFields(Class<?> type) {
        List<java.lang.reflect.Field> fields = new ArrayList<>();
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) hierarchy.add(c);
        Collections.reverse(hierarchy);
        for (Class<?> c : hierarchy) Collections.addAll(fields, c.getDeclaredFields());
        return fields;
    }

    public T newInstance() {
        try { return constructor.newInstance(); }
        catch (ReflectiveOperationException e) { throw new DataException("Cannot instantiate " + type.getName(), e); }
    }

    /** Returns the configured group key from an entity. Single-field groups return that field directly. */
    public Object groupKey(Object entity) {
        requireGroupKey();
        if (groupKeyProperties.size() == 1) return groupKeyProperties.getFirst().get(entity);
        Object[] values = new Object[groupKeyProperties.size()];
        for (int i = 0; i < values.length; i++) values[i] = groupKeyProperties.get(i).get(entity);
        return GroupKeys.of(values);
    }

    /** Uses @Id by default, a single @MapKey value directly, or a colon-separated string for multiple fields. */
    public Object mapKey(Object entity) {
        if (mapKeyProperties.isEmpty()) return idProperty.get(entity);
        if (mapKeyProperties.size() == 1) return mapKeyProperties.getFirst().get(entity);
        Object[] values = new Object[mapKeyProperties.size()];
        for (int i = 0; i < values.length; i++) values[i] = mapKeyProperties.get(i).get(entity);
        return MapKeys.of(values);
    }

    /** Returns the values represented by a GroupRepository key, in @GroupKey order. */
    public List<Object> groupKeyValues(Object groupKey) {
        requireGroupKey();
        if (groupKeyProperties.size() == 1) {
            if (!groupKeyType.isInstance(groupKey)) {
                throw new DataException("Group key must be " + groupKeyType.getName() + ": " + type.getName());
            }
            return List.of(groupKey);
        }
        if (!(groupKey instanceof String key)) {
            throw new DataException("Composite group key must be a colon-separated String: " + type.getName());
        }
        String[] parts = key.split(":", -1);
        if (parts.length != groupKeyProperties.size()) {
            throw new DataException("Composite group key size mismatch for " + type.getName()
                    + ": expected=" + groupKeyProperties.size() + ", actual=" + parts.length);
        }
        List<Object> values = new ArrayList<>(parts.length);
        try {
            for (int i = 0; i < parts.length; i++) {
                values.add(parseKeyPart(parts[i], groupKeyProperties.get(i).type()));
            }
        } catch (RuntimeException error) {
            throw new DataException("Invalid composite group key for " + type.getName() + ": " + key, error);
        }
        return values;
    }

    /** Builds the equality query using the original declared field types. */
    public Query groupQuery(Object groupKey) {
        List<Object> values = groupKeyValues(groupKey);
        Query query = Query.where(groupKeyProperties.getFirst().propertyName(), values.getFirst());
        for (int i = 1; i < groupKeyProperties.size(); i++) {
            query = query.and(groupKeyProperties.get(i).propertyName(), values.get(i));
        }
        return query;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object parseKeyPart(String value, Class<?> type) {
        if (type == String.class) return value;
        if (type == long.class || type == Long.class) return Long.valueOf(value);
        if (type == int.class || type == Integer.class) return Integer.valueOf(value);
        if (type == short.class || type == Short.class) return Short.valueOf(value);
        if (type == byte.class || type == Byte.class) return Byte.valueOf(value);
        if (type == double.class || type == Double.class) return Double.valueOf(value);
        if (type == float.class || type == Float.class) return Float.valueOf(value);
        if (type == java.math.BigInteger.class) return new java.math.BigInteger(value);
        if (type == java.math.BigDecimal.class) return new java.math.BigDecimal(value);
        if (type == UUID.class) return UUID.fromString(value);
        if (type == java.time.LocalDate.class) return java.time.LocalDate.parse(value);
        if (type.isEnum()) return Enum.valueOf((Class<? extends Enum>) type, value);
        if ((type == char.class || type == Character.class) && value.length() == 1) return value.charAt(0);
        if ((type == boolean.class || type == Boolean.class) && (value.equals("true") || value.equals("false"))) {
            return Boolean.valueOf(value);
        }
        throw new IllegalArgumentException("Unsupported composite key value/type: " + value + "/" + type.getName());
    }

    private void requireGroupKey() {
        if (groupKeyProperties.isEmpty()) {
            throw new DataException(type.getName() + " must declare at least one @GroupKey field for GroupRepository");
        }
    }

    public Class<T> type() { return type; }
    public String rdbTable() { return rdbTable; }
    public String rdbSchema() { return rdbSchema; }
    public String docCollection() { return docCollection; }
    public String sqlTable() { return rdbTable; }
    public String sqlSchema() { return rdbSchema; }
    public String mongoCollection() { return docCollection; }
    public List<PropertyMetadata> properties() { return properties; }
    public PropertyMetadata idProperty() { return idProperty; }
    public List<PropertyMetadata> groupKeyProperties() { return groupKeyProperties; }
    public List<PropertyMetadata> mapKeyProperties() { return mapKeyProperties; }
    public List<IndexMetadata> indexes() { return indexes; }
    public List<CompoundIndex> compoundIndexes() { return compoundIndexes; }
    public CacheMode cacheMode() { return cacheMode; }
    public boolean resident() { return cacheMode == CacheMode.RESIDENT; }

    public PropertyMetadata property(String name) {
        PropertyMetadata p = propertiesByName.get(name);
        if (p == null) throw new DataException("Unknown property '" + name + "' on " + type.getName());
        return p;
    }
}
