package cn.managame.data.mysql;

import cn.managame.data.codec.*;
import cn.managame.data.meta.*;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.function.Function;

final class MysqlFieldMeta {
    interface Reader { Object read(ResultSet row, int index) throws SQLException; }
    final FieldAccessor accessor;
    final String name, sqlType, defaultValue;
    final boolean identity;
    private final Function<Object,Object> encoder;
    private final Reader reader;

    MysqlFieldMeta(Field field, boolean identity, JsonCodec json, BinaryCodec binary) {
        this(field, identity, json, binary, null);
    }
    MysqlFieldMeta(Field field, boolean identity, JsonCodec json, BinaryCodec binary, Object prototype) {
        accessor = new FieldAccessor(field); this.identity = identity;
        Column column = field.getAnnotation(Column.class);
        name = SqlNames.identifier(column.name().isEmpty() ? SqlNames.snake(field.getName()) : column.name());
        Class<?> t = EntityMeta.boxed(field.getType());
        String automaticDefault = "";
        switch (column.type()) {
            case JSON -> {
                sqlType = "JSON";
                if (t == String.class) { encoder = v -> v; reader = ResultSet::getString; }
                else {
                    if (json == null) json = JsonCodec.defaultCodec();
                    encoder = json::encode;
                    Object initialized = prototype == null ? null : accessor.get(prototype);
                    var decoder = java.util.Objects.requireNonNull(json.decoder(accessor.genericType(),
                            initialized == null ? null : initialized.getClass()), "JSON field decoder");
                    reader = (r, i) -> { String value = r.getString(i); return value == null ? null : decoder.apply(value); };
                }
            }
            case BINARY -> {
                sqlType = "BLOB";
                if (t == byte[].class) { encoder = v -> v; reader = ResultSet::getBytes; }
                else {
                    if (binary == null) throw new IllegalArgumentException("BinaryCodec required: " + field);
                    encoder = binary::encode;
                    reader = (r, i) -> { byte[] value = r.getBytes(i); return value == null ? null : binary.decode(value, accessor.genericType()); };
                }
            }
            case TEXT -> {
                if (t != String.class) throw new IllegalArgumentException("TEXT requires String: " + field);
                sqlType = "TEXT"; encoder = v -> v; reader = ResultSet::getString;
            }
            case DEFAULT -> {
                encoder = v -> v;
                if (t == Long.class) { sqlType = "BIGINT"; reader = ResultSet::getLong; automaticDefault = "0"; }
                else if (t == Integer.class) { sqlType = "INT"; reader = ResultSet::getInt; automaticDefault = "0"; }
                else if (t == Short.class) { sqlType = "SMALLINT"; reader = ResultSet::getShort; automaticDefault = "0"; }
                else if (t == Byte.class) { sqlType = "TINYINT"; reader = ResultSet::getByte; automaticDefault = "0"; }
                else if (t == Boolean.class) { sqlType = "TINYINT"; reader = ResultSet::getBoolean; automaticDefault = "0"; }
                else if (t == Float.class) { sqlType = "FLOAT"; reader = ResultSet::getFloat; automaticDefault = "0"; }
                else if (t == Double.class) { sqlType = "DOUBLE"; reader = ResultSet::getDouble; automaticDefault = "0"; }
                else if (t == String.class) { sqlType = "VARCHAR(255)"; reader = ResultSet::getString; automaticDefault = "''"; }
                else if (t == Character.class) {
                    sqlType = "CHAR(1)"; automaticDefault = "''";
                    reader = (r, i) -> { String s = r.getString(i); return s == null ? null : s.isEmpty() ? '\0' : s.charAt(0); };
                }
                else if (t == byte[].class) { sqlType = "BLOB"; reader = ResultSet::getBytes; }
                else throw new IllegalArgumentException("DEFAULT does not support " + field);
            }
            default -> throw new AssertionError(column.type());
        }
        if (identity && column.type() != ColumnType.DEFAULT)
            throw new IllegalArgumentException("Identity requires DEFAULT scalar column: " + field);
        defaultValue = column.defaultValue().isEmpty() ? automaticDefault : column.defaultValue();
    }
    Object encode(Object entity) {
        Object value = accessor.get(entity); return value == null ? null : encoder.apply(value);
    }
    void read(Object entity, ResultSet row, int index) throws SQLException {
        Object value = reader.read(row, index);
        if (row.wasNull()) {
            if (accessor.type().isPrimitive()) throw new SQLException("NULL for primitive field " + accessor.field());
            value = null;
        }
        accessor.set(entity, value);
    }
    String definition() {
        return SqlNames.quote(name) + " " + sqlType + (identity || accessor.type().isPrimitive() ? " NOT NULL" : "")
                + (defaultValue.isEmpty() ? "" : " DEFAULT " + defaultValue);
    }
}
