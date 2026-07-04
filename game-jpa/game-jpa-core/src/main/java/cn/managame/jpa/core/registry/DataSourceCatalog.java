package cn.managame.jpa.core.registry;

import java.util.Set;

/**
 * 执行器对外暴露其已注册数据源名集合的 SPI，供 bootstrap 启动期校验「实体 home 数据源都已注册」。
 * <p>
 * 多数据源执行器（如 MySQL / Mongo）实现本接口；据此可在布局阶段就发现路由/绑定产出的库名未注册，
 * 而不是等到第一次写入才在运行期暴露并丢弃数据。
 */
public interface DataSourceCatalog {

    /** 已注册的数据源名集合（含 {@code "default"}）。 */
    Set<String> dataSourceNames();
}
