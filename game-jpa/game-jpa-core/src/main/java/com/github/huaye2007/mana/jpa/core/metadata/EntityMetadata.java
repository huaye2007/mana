package com.github.huaye2007.mana.jpa.core.metadata;

import com.github.huaye2007.mana.jpa.core.bootstrap.ModelType;

/**
 * 通用实体元数据接口。
 * 所有模型层的元数据都必须实现此接口。
 */
public interface EntityMetadata {

    /**
     * 实体 Java 类型
     */
    Class<?> entityType();

    /**
     * 所属存储模型
     */
    ModelType modelType();

    /**
     * 逻辑名称（用于注册、日志、监控）
     */
    String logicalName();

    /**
     * 实体所属的 home 数据源名（"实体住在哪个库"）。默认 {@code "default"}（游戏库）。
     * 模型层从注解（如 {@code @Table(dataSource=...)} / {@code @Document(dataSource=...)}）解析；
     * 未声明则由 {@code DataSourceBinding} 按类/包注册回退，最终回退 {@code "default"}。
     * 非分片读写以此为目标库；分片实体的库由 {@code RoutingStrategy} 决定。
     */
    default String dataSourceName() {
        return "default";
    }

    /**
     * 主键字段。具体模型层（RDB/DocDB）以协变返回类型覆盖。
     * <p>
     * 注意：此处必须是抽象方法而非 default。若声明为 default，子类的协变返回覆盖
     * （如 {@code RdbFieldMetadata idField()}）不会生成覆盖 default 的桥接方法，
     * 通过 {@code EntityMetadata} 接口调用时会落到 default 实现上。
     */
    FieldMetadata idField();

    /**
     * 分片键字段，未配置 {@code @ShardKey} 时为 {@code null}。
     */
    FieldMetadata shardKeyField();

    /**
     * 角色 ID 字段，未配置时为 {@code null}（用于新角色缓存穿透策略）。
     */
    FieldMetadata roleIdField();

    /**
     * 是否配置了分片键。
     */
    default boolean hasShardKey() {
        return shardKeyField() != null;
    }

    /**
     * 是否配置了角色 ID 字段。
     */
    default boolean hasRoleId() {
        return roleIdField() != null;
    }
}
