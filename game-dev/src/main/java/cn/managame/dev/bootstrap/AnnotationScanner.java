package cn.managame.dev.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 启动期注解处理器：从容器中取出标注了某注解（如 {@code @GameController}、
 * {@code @EventHandler}）的 bean 实例，交给调用方做自定义处理（例如注册到
 * {@code CommandRegistry} / {@code EventBus}）。
 *
 * <p>实例由 Spring 容器统一管理（完成依赖注入、生命周期回调），这里只负责取出并分发，
 * 不自行 new、也不脱离容器创建。前提是这些注解类已被纳入组件扫描——注解定义在
 * game-runtime（不带 {@code @Component}、不依赖 Spring），由宿主在启动扫描时通过
 * include filter 接管，符合 runtime 零依赖、宿主自行注册的约定。</p>
 *
 * <p>仅用于启动期、单线程调用；下游注册表（CommandRegistry/EventBus）运行期只读。</p>
 */
public final class AnnotationScanner {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationScanner.class);

    private final ApplicationContext applicationContext;

    public AnnotationScanner(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 取出容器中所有标注了 {@code annotation} 的 bean 实例，逐个交给 {@code processor} 处理。
     */
    public void process(Class<? extends Annotation> annotation, Consumer<Object> processor) {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(annotation);
        beans.values().forEach(processor);
        logger.info("@{} 处理 {} 个 bean", annotation.getSimpleName(), beans.size());
    }
}
