package cn.managame.data.mysql;

import cn.managame.data.EntityField;
import cn.managame.data.EntityType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One saved field as a MySQL column. Scalars (the primitives and their boxes, String, char and byte[]) are columns of
 * their own; anything else is JSON through the application's {@link JsonCodec}.
 */
final class MysqlColumn {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,63}");

    private interface Reader {
        Object read(ResultSet resultSet, int index) throws SQLException;
    }

    final EntityField field;
    final String name;
    /** Reads a scalar column; null for a JSON one. */
    private final Reader scalarReader;
    /** Encodes and decodes a JSON column; null for a scalar one. */
    private final JsonCodec jsonCodec;

    MysqlColumn(EntityField field, JsonCodec codec) {
        this.field = field;
        Column column = field.field().getAnnotation(Column.class);
        name = checkName(column == null ? snakeCase(field.name()) : column.name());
        scalarReader = scalarReader(EntityType.boxed(field.type()));
        if (scalarReader == null && codec == null)
            throw new IllegalStateException(field.field() + " is saved as JSON, which needs a JsonCodec");
        jsonCodec = scalarReader == null ? codec : null;
    }

    /** The column value of the entity's field, as JDBC binds it. */
    Object encode(Object entity) {
        Object value = field.get(entity);
        if (value == null) return null;
        if (jsonCodec == null) return value instanceof Character c ? String.valueOf(c) : value;
        try {
            return jsonCodec.encode(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cannot encode " + field.field() + " as JSON", e);
        }
    }

    /** Sets the entity's field from column {@code index} of the result set's current row. */
    void read(Object entity, ResultSet resultSet, int index) throws SQLException {
        Object value;
        if (jsonCodec == null) {
            value = scalarReader.read(resultSet, index);
            if (resultSet.wasNull()) value = null;
        } else {
            String json = resultSet.getString(index);
            try {
                value = json == null ? null : jsonCodec.decode(json, field.valueType());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("cannot decode " + field.field() + " from JSON", e);
            }
        }
        if (value == null && field.type().isPrimitive())
            throw new SQLException("column " + name + " is NULL for primitive field " + field.field());
        field.set(entity, value);
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

    private static Reader scalarReader(Class<?> type) {
        if (type == Long.class) return ResultSet::getLong;
        if (type == Integer.class) return ResultSet::getInt;
        if (type == Short.class) return ResultSet::getShort;
        if (type == Byte.class) return ResultSet::getByte;
        if (type == Boolean.class) return ResultSet::getBoolean;
        if (type == Double.class) return ResultSet::getDouble;
        if (type == Float.class) return ResultSet::getFloat;
        if (type == String.class) return ResultSet::getString;
        if (type == byte[].class) return ResultSet::getBytes;
        if (type == Character.class) return (resultSet, index) -> {
            String text = resultSet.getString(index);
            return text == null || text.isEmpty() ? null : text.charAt(0);
        };
        return null;
    }
}
