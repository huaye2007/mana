package cn.managame.jpa.core.bootstrap;

/**
 * 存储模型类型标识。
 * 每种数据库模型（RDB / DOCDB）对应一个 ModelType 实例。
 */
public interface ModelType {

    /**
     * 模型名称，如 "rdb"、"docdb"
     */
    String modelName();
}
