package cn.managame.data.mysql;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * How one field is kept in its column, for a field the mapper's own rules would keep otherwise: an enum as its name,
 * an Instant as a DATETIME, a message as its bytes. The application gives the mapper a function that picks the codec of
 * a field ({@code MysqlEntityMapper.builder(dataSource).columnCodecs(...)}), asked once for each field while GameData
 * is built; a field it returns null for is kept by the rules: a scalar as its own column type, anything else as JSON.
 *
 * <p>Called from write and load threads at once.
 */
public interface ColumnCodec {
    /** The column's MySQL type, as written in a CREATE TABLE ({@code "INT"}, {@code "DATETIME(3)"}, {@code "MEDIUMBLOB"}): what the mapper creates the column as. */
    String columnType();

    /** What JDBC binds for {@code value}, the field's value, which is never null: null is written as NULL without asking. */
    Object encode(Object value);

    /**
     * The field's value from column {@code index} of the result set's current row, or null for a column that is NULL,
     * which leaves the field as the entity's constructor made it.
     */
    Object decode(ResultSet resultSet, int index) throws SQLException;
}
