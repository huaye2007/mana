package cn.managame.data.mysql;
@FunctionalInterface
public interface TransactionAction<T> { T execute(MysqlTransaction tx) throws Exception; }
