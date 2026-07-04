package cn.managame.jpa.core.repository;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标识一个接口是 game-jpa 管理的 Repository,用于表达意图、提升可读性。
 *
 * <p>这是一个 <b>DI 容器中立</b> 的标记注解:它不是 Spring 的 {@code @Repository},不会触发任何容器的
 * 组件扫描。game-jpa 的 Repository 实例由框架(扫包 + {@code RepositoryFactory})创建,再由宿主桥接进
 * Spring 等容器;给接口贴 Spring {@code @Repository} 会误导读者以为它能被 {@code @ComponentScan} 扫到,
 * 因此用本注解明确"由 game-jpa 装配"的真实来源。</p>
 *
 * <p>注解是<b>可选</b>的:{@code GameJpaBootstrap.scanPackages(...)} 仍以 {@code RepositoryFactory.supports}
 * 判定 Repository,标不标注都能被发现和实例化。它的价值在于让 Spring 项目里被 {@code @Autowired} 注入的
 * 裸接口有清晰身份,类比 MyBatis 的 {@code @Mapper}。</p>
 *
 * <pre>{@code
 * @GameRepository
 * public interface UserRepository extends IRdbUniqueCacheRepository<User, Long> {
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface GameRepository {
}
