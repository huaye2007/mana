package cn.managame.data.mysql;
public interface MysqlAccess extends MysqlTransaction {
    <T> T transaction(TransactionAction<T> action);

    /**
     * Whether the database runs the multi-row UPDATE MysqlEntityMapper writes — one statement updating many rows
     * by primary key through CASE, with update counts that report matched rather than changed rows, as MySQL and
     * MariaDB do with Connector/J's defaults. False keeps one UPDATE per row; that is the default, so an Access
     * written for something else is never handed a statement it was not built for.
     */
    default boolean supportsMultiRowUpdate() { return false; }
}
