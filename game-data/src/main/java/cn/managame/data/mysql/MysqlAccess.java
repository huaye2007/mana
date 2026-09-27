package cn.managame.data.mysql;
public interface MysqlAccess extends MysqlTransaction {
    <T> T transaction(TransactionAction<T> action);
}
