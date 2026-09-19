package cn.managame.core.metadata;

import cn.managame.core.DataException;

import cn.managame.annotation.ColumnType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public final class PropertyMetadata {
    private final Field field;
    private final String propertyName;
    private final String rdbName;
    private final String docName;
    private final boolean id;
    private final MethodHandle getter;
    private final MethodHandle setter;
    private final StorageKind storageKind;
    private final ColumnType columnType;
    private final int length;

    PropertyMetadata(Field field, String propertyName, String rdbName, String docName, boolean id,
                     ColumnType columnType, int length) {
        this.field = field;
        this.propertyName = propertyName;
        this.rdbName = rdbName;
        this.docName = docName;
        this.id = id;
        this.columnType = columnType == null ? ColumnType.AUTO : columnType;
        if (length <= 0) throw new DataException("@Column length must be > 0: " + field);
        this.length = length;
        this.storageKind = inferStorageKind(field.getType(), this.columnType);
        validateExplicitType(field, this.columnType);
        try {
            field.setAccessible(true);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup());
            this.getter = lookup.unreflectGetter(field);
            this.setter = lookup.unreflectSetter(field);
        } catch (IllegalAccessException e) {
            throw new DataException("Cannot access field " + field, e);
        }
    }

    public String propertyName() { return propertyName; }
    public String rdbName() { return rdbName; }
    public String docName() { return docName; }
    public String sqlName() { return rdbName; }
    public String mongoName() { return docName; }
    public Class<?> type() { return field.getType(); }
    public Type genericType() { return field.getGenericType(); }
    public boolean id() { return id; }
    public Field field() { return field; }
    public StorageKind storageKind() { return storageKind; }
    public ColumnType columnType() { return columnType; }
    public int length() { return length; }

    public Object get(Object target) {
        try { return getter.invoke(target); }
        catch (Throwable e) { throw new DataException("Cannot read " + field, e); }
    }

    public void set(Object target, Object value) {
        try { setter.invoke(target, value); }
        catch (Throwable e) { throw new DataException("Cannot write " + field, e); }
    }

    private static StorageKind inferStorageKind(Class<?> type, ColumnType columnType) {
        if (columnType == ColumnType.JSON) return StorageKind.JSON;
        if (columnType == ColumnType.BINARY) return StorageKind.BINARY;
        if (columnType == ColumnType.STRING || columnType == ColumnType.TEXT) return StorageKind.SCALAR;
        if (type == byte[].class) return StorageKind.BINARY;
        if (isScalar(type)) return StorageKind.SCALAR;
        if (type.isArray() || Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            return StorageKind.JSON;
        }
        return StorageKind.JSON;
    }

    private static void validateExplicitType(Field field, ColumnType columnType) {
        Class<?> type = field.getType();
        if ((columnType == ColumnType.STRING || columnType == ColumnType.TEXT)
                && type != String.class && !type.isEnum() && type != UUID.class) {
            throw new DataException("@Column(type=" + columnType + ") requires String/Enum/UUID field: " + field);
        }
        if (columnType == ColumnType.BINARY && type != byte[].class) {
            throw new DataException("@Column(type=BINARY) currently requires byte[] field: " + field);
        }
    }

    private static boolean isScalar(Class<?> type) {
        if (type.isPrimitive() || type.isEnum()) return true;
        if (Number.class.isAssignableFrom(type) || type == String.class || type == Boolean.class
                || type == Character.class || type == UUID.class || type == BigDecimal.class
                || type == BigInteger.class) return true;
        return type == Instant.class || type == LocalDateTime.class || type == LocalDate.class
                || type == LocalTime.class || type == OffsetDateTime.class || type == OffsetTime.class;
    }
}
