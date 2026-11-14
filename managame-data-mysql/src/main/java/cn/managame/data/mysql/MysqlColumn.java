package cn.managame.data.mysql;

import cn.managame.data.mysql.annotation.Column;
import cn.managame.data.spi.EntityField;
import cn.managame.data.spi.EntityType;
import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * One saved field as a MySQL column: its name, how its value is kept, and what the column is created as. A field is
 * kept by the codec the application's function picks for it, or else by the rules: a scalar (the primitives and their
 * boxes, String, char and byte[]) as a column of its own type, anything else as JSON through the application's
 * {@link JsonCodec}.
 */
final class MysqlColumn {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,63}");

    private interface Reader {
        Object read(ResultSet resultSet, int index) throws SQLException;
    }

    final EntityField field;
    final String name;
    /** What follows the column's name in a CREATE TABLE or an ADD COLUMN: its type, whether it may be NULL, its DEFAULT. */
    final String definition;
    private final ColumnCodec codec;

    /**
     * @param key         whether the field is the id or a group or map key, whose column is NOT NULL and has no DEFAULT
     * @param newInstance an entity as its no-argument constructor makes it, whose value a primitive field's column defaults to
     * @param jsonCodec   keeps what is neither a scalar nor has a codec of its own; null when no field needs it
     * @param codecs      the application's choice of a codec for a field, null for a field kept by the rules
     */
    MysqlColumn(EntityField field, boolean key, Object newInstance, JsonCodec jsonCodec, Function<EntityField, ColumnCodec> codecs) {
        this.field = field;
        Column column = field.field().getAnnotation(Column.class);
        name = checkName(column == null || column.name().isEmpty() ? snakeCase(field.name()) : column.name());
        // A key is bound as it is where rows are looked for by it, so it is kept as it is: no codec is asked for one.
        ColumnCodec own = key ? null : codecs.apply(field), scalar = own != null ? null : scalarCodec(EntityType.boxed(field.type()));
        if (own == null && scalar == null && jsonCodec == null)
            throw new IllegalStateException(field.field() + " is saved as JSON, which needs a JsonCodec");
        codec = own != null ? own : scalar != null ? scalar : jsonColumn(jsonCodec, field.valueType());
        String type = column == null || column.type().isEmpty() ? codec.columnType() : column.type();
        String defaultValue = column != null && !column.defaultValue().isEmpty() ? column.defaultValue()
                : field.type().isPrimitive() && !key ? literal(encode(newInstance)) : null;
        definition = type + (key || field.type().isPrimitive() ? " NOT NULL" : "") + (defaultValue == null || key ? "" : " DEFAULT " + defaultValue);
    }

    /** The column value of the entity's field, as JDBC binds it. */
    Object encode(Object entity) {
        Object value = field.get(entity);
        if (value == null) return null;
        try {
            return codec.encode(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cannot encode " + field.field(), e);
        }
    }

    /**
     * Sets the entity's field from column {@code index} of the result set's current row. A column that is NULL leaves
     * the field as the constructor made it, as in a row stored before the column was added.
     */
    void read(Object entity, ResultSet resultSet, int index) throws SQLException {
        Object value;
        try {
            value = codec.decode(resultSet, index);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cannot decode " + field.field(), e);
        }
        if (value != null) field.set(entity, value);
    }

    String quotedName() { return quote(name); }

    static String checkName(String name) {
        if (!IDENTIFIER.matcher(name).matches()) throw new IllegalArgumentException("not a usable MySQL name: " + name);
        return name;
    }

    static String quote(String name) { return "`" + name + "`"; }

    static String snakeCase(String name) {
        return name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /** A value as JDBC binds it, written as SQL writes it after DEFAULT; null for one SQL has no such form for. */
    private static String literal(Object value) {
        if (value instanceof Boolean flag) return flag ? "1" : "0";
        if (value instanceof Number) return value.toString();
        if (value instanceof String text) return "'" + text.replace("\\", "\\\\").replace("'", "''").replace("\0", "") + "'";
        return null;
    }

    /** The mapper's own way to keep a scalar, as the column type that holds it; null for a class that is none. */
    private static ColumnCodec scalarCodec(Class<?> type) {
        if (type == Long.class) return scalar("BIGINT", ResultSet::getLong);
        if (type == Integer.class) return scalar("INT", ResultSet::getInt);
        if (type == Short.class) return scalar("SMALLINT", ResultSet::getShort);
        if (type == Byte.class) return scalar("TINYINT", ResultSet::getByte);
        if (type == Boolean.class) return scalar("TINYINT(1)", ResultSet::getBoolean);
        if (type == Double.class) return scalar("DOUBLE", ResultSet::getDouble);
        if (type == Float.class) return scalar("FLOAT", ResultSet::getFloat);
        if (type == String.class) return scalar("VARCHAR(255)", ResultSet::getString);
        if (type == byte[].class) return scalar("MEDIUMBLOB", ResultSet::getBytes);
        if (type == Character.class) return new ColumnCodec() {
            @Override public String columnType() { return "CHAR(1)"; }

            @Override public Object encode(Object value) { return String.valueOf(value); }

            @Override public Object decode(ResultSet resultSet, int index) throws SQLException {
                String text = resultSet.getString(index);
                return text == null || text.isEmpty() ? null : text.charAt(0);
            }
        };
        return null;
    }

    private static ColumnCodec scalar(String columnType, Reader reader) {
        return new ColumnCodec() {
            @Override public String columnType() { return columnType; }

            @Override public Object encode(Object value) { return value; }

            @Override public Object decode(ResultSet resultSet, int index) throws SQLException {
                Object value = reader.read(resultSet, index);
                return resultSet.wasNull() ? null : value;
            }
        };
    }

    /** A field kept as JSON text, in a column no document outgrows. */
    private static ColumnCodec jsonColumn(JsonCodec jsonCodec, Type valueType) {
        return new ColumnCodec() {
            @Override public String columnType() { return "MEDIUMTEXT"; }

            @Override public Object encode(Object value) { return jsonCodec.encode(value); }

            @Override public Object decode(ResultSet resultSet, int index) throws SQLException {
                String json = resultSet.getString(index);
                return json == null ? null : jsonCodec.decode(json, valueType);
            }
        };
    }
}
