package com.github.huaye2007.mana.jpa.core.routing;

/**
 * 路由策略 SPI。
 * 根据路由键决定目标数据源或物理表/集合。
 */
public interface RoutingStrategy {

    /**
     * 根据逻辑名和路由键计算目标数据源名
     */
    String resolveDataSource(String logicalName, Object routingKey);

    /**
     * 根据逻辑名和路由键计算物理名（表名 / collection / key 前缀等）
     */
    String resolvePhysicalName(String logicalName, Object routingKey);
}
