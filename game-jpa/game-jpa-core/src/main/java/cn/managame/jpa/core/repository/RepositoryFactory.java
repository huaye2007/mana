package cn.managame.jpa.core.repository;

import cn.managame.jpa.core.context.ComponentRegistry;

/**
 * Repository 工厂 SPI。
 * 每种模型的实现层提供自己的工厂，负责创建 Repository 代理实例。
 */
public interface RepositoryFactory {

    /**
     * Factory priority used when more than one factory supports the same repository
     * interface. Higher values win.
     */
    default int priority() {
        return 0;
    }

    /**
     * 判断是否支持创建该 Repository 类型
     */
    boolean supports(Class<?> repositoryType);

    /**
     * 创建 Repository 实例
     */
    Object createRepository(Class<?> repositoryType, ComponentRegistry registry);
}
