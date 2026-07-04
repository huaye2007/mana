package cn.managame.jpa.starter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link GameJpaBootstrap#scanPackages} 的结果:持有创建好的 {@link GameJpaContext}
 * 以及扫描到并已实例化的 Repository(接口 {@code Class} -> 实例)。
 *
 * <p>框架本身不依赖任何 DI 容器。需要接入 Spring 等容器时,宿主遍历 {@link #repositories()}
 * 把实例注册为单例即可,例如:</p>
 *
 * <pre>{@code
 * GameJpaScan scan = new GameJpaBootstrap()
 *         .install(RdbCacheModule.withExecutor(executor))
 *         .scanPackages("cn.managame.dev");
 * scan.repositories().forEach((type, bean) ->
 *         beanFactory.registerSingleton(Introspector.decapitalize(type.getSimpleName()), bean));
 * }</pre>
 */
public final class GameJpaScan {

    private final GameJpaContext context;
    private final Map<Class<?>, Object> repositories;

    GameJpaScan(GameJpaContext context, Map<Class<?>, Object> repositories) {
        this.context = context;
        this.repositories = Collections.unmodifiableMap(new LinkedHashMap<>(repositories));
    }

    /** 创建好的运行时上下文;须在服务关闭时调用 {@link GameJpaContext#close()}。 */
    public GameJpaContext context() {
        return context;
    }

    /** 扫描到的 Repository:接口 {@code Class} -> 实例(已是接口类型,可直接强转)。保持扫描顺序、只读。 */
    public Map<Class<?>, Object> repositories() {
        return repositories;
    }

    /** 按接口类型取出已实例化的 Repository;不存在返回 {@code null}。 */
    @SuppressWarnings("unchecked")
    public <R> R repository(Class<R> repositoryType) {
        return (R) repositories.get(repositoryType);
    }
}
