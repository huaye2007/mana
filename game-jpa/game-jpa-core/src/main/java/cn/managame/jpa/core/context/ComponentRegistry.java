package cn.managame.jpa.core.context;

/**
 * 组件注册表接口。
 * 提供按类型注册/获取任意组件的能力，作为框架的服务定位器。
 * <p>
 * GameJpaContext 实现此接口，所有 Factory 和 Repository 通过此接口获取依赖。
 */
public interface ComponentRegistry {

    /** 注册组件 */
    <T> void register(Class<T> type, T component);

    /** 按类型获取组件，不存在则抛异常 */
    <T> T get(Class<T> type);

    /** 按类型查找组件，不存在返回 null */
    <T> T find(Class<T> type);
}
