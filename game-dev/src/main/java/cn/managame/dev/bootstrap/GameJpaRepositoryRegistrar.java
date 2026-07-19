package cn.managame.dev.bootstrap;

import cn.managame.jpa.core.bootstrap.GameJpaExtension;
import cn.managame.jpa.starter.GameJpaBootstrap;
import cn.managame.jpa.starter.GameJpaContext;
import cn.managame.jpa.starter.GameJpaScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.beans.Introspector;

/**
 * 把 game-jpa 扫描出的 Repository 实例注册进 Spring 容器 —— 宿主侧薄桥接。
 *
 * <p>"扫包 -> 装配实体 -> 实例化 Repository"已由 game-jpa 核心
 * {@link GameJpaBootstrap#scanPackages} 完成(框架 DI 中立、不依赖 Spring);这里只负责把结果
 * 按类型注册为 Spring 单例,供 {@code @Autowired} 注入。必须在
 * {@code ApplicationContext.refresh()} 之前调用,否则 controller 注入时容器里还没有对应 bean。</p>
 */
public final class GameJpaRepositoryRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(GameJpaRepositoryRegistrar.class);

    private GameJpaRepositoryRegistrar() {
    }

    /**
     * 扫描 {@code basePackage} 装配 game-jpa,并把每个 Repository 注册为 Spring 单例。
     * 返回的 {@link GameJpaContext} 须在服务关闭时 {@code close()}(触发最终刷盘)。
     */
    public static GameJpaContext registerInto(ConfigurableListableBeanFactory beanFactory,
                                              String basePackage,
                                              GameJpaExtension... extensions) {
        GameJpaBootstrap bootstrap = new GameJpaBootstrap();
        for (GameJpaExtension extension : extensions) {
            bootstrap.use(extension);
        }
        GameJpaScan scan = bootstrap.scanPackages(basePackage);
        scan.repositories().forEach((type, bean) -> {
            String beanName = Introspector.decapitalize(type.getSimpleName());
            beanFactory.registerSingleton(beanName, bean);
            logger.info("注册 game-jpa repository 单例: {} -> {}", beanName, type.getName());
        });
        logger.info("game-jpa 已注册 {} 个 repository 到 Spring 容器", scan.repositories().size());
        return scan.context();
    }
}
