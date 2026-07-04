package cn.managame.dev.bootstrap;

import cn.managame.dev.bus.login.LoginController;
import cn.managame.dev.bus.login.UserRepository;
import cn.managame.runtime.annotation.EventHandler;
import cn.managame.runtime.annotation.GameController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证：仅靠 include filter（注解类不带 @Component），@GameController 标注的类
 * 也会被注册为容器受管 bean，且 {@link AnnotationScanner#process} 把这些受管实例
 * 交给自定义处理器。这里用本地收集器断言，不去改全局 CommandRegistry 单例
 * （真正的注册链路由 GamePacketCodecTest 覆盖），避免跨测试污染。
 */
class AnnotationScannerTest {

    @Test
    void includeFilterRegistersControllerAsManagedBean() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ClassPathBeanDefinitionScanner beanScanner = new ClassPathBeanDefinitionScanner(ctx, true);
            beanScanner.addIncludeFilter(new AnnotationTypeFilter(GameController.class));
            beanScanner.addIncludeFilter(new AnnotationTypeFilter(EventHandler.class));
            beanScanner.scan("cn.managame.dev");
            // 复刻 Game.main 的契约：jpa Repository 单例须在 refresh 前注册，
            // 否则 LoginController 的 @Autowired UserRepository 无 bean 可注入。
            // 这里只测扫描/注册链路，不连数据库，用永不调用的桩顶替。
            ctx.getDefaultListableBeanFactory().registerSingleton("userRepository",
                    Proxy.newProxyInstance(UserRepository.class.getClassLoader(),
                            new Class<?>[]{UserRepository.class},
                            (proxy, method, args) -> {
                                throw new UnsupportedOperationException("test stub");
                            }));
            ctx.refresh();

            // @GameController 类已成为容器受管 bean
            Map<String, Object> controllers = ctx.getBeansWithAnnotation(GameController.class);
            assertTrue(controllers.values().stream().anyMatch(b -> b instanceof LoginController),
                    "LoginController 应被容器管理");

            // 按注解取到的实例与按类型取到的是同一个单例（确认是容器统一管理，而非各自 new）
            assertSame(ctx.getBean(LoginController.class),
                    controllers.get("loginController"));

            // process() 把容器中的受管实例逐个交给自定义处理器
            List<Object> processed = new ArrayList<>();
            new AnnotationScanner(ctx).process(GameController.class, processed::add);
            assertTrue(processed.stream().anyMatch(b -> b instanceof LoginController),
                    "process 应把 @GameController 实例交给处理器");
        }
    }
}
