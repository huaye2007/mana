package cn.managame.spring.runtime;

import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.timer.Cron;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CronBeansTest {
    @Configuration @EnableGameRuntime(basePackages = "cn.managame.spring.runtime")
    static class TestConfiguration {
        @Bean(destroyMethod = "close") cn.managame.runtime.executor.RouteExecutor routeExecutor() {
            return cn.managame.runtime.executor.RouteExecutors.virtualThreads();
        }
        @Bean GameRuntimeConfigurer configurer(cn.managame.runtime.executor.RouteExecutor executor) {
            return builder -> builder.routeDomains(java.util.List.of(cn.managame.runtime.route.RouteDomain.of(3, "system")))
                    .routeExecutors(java.util.List.of(cn.managame.runtime.executor.RouteExecutorBinding.of(executor, 3)));
        }
    }
    record Fired(int domain, long key, boolean virtual) {}

    @Profile("cron-auto-scan-test")
    public static class ScannedTask {
        final LinkedBlockingQueue<Fired> fired = new LinkedBlockingQueue<>();
        @Cron(value = "* * * * * ?", domain = 3, routeKey = 7L)
        public void tick() {
            var context = Contexts.current();
            fired.add(new Fired(context.routeDomain(), context.routeKey(), Thread.currentThread().isVirtual()));
        }
    }

    @Profile("factory-cron-only")
    public static class FactoryTask {
        final LinkedBlockingQueue<Fired> fired = new LinkedBlockingQueue<>();
        @Cron(value = "* * * * * ?", domain = 3, routeKey = 8L)
        public void tick() {
            var context = Contexts.current();
            fired.add(new Fired(context.routeDomain(), context.routeKey(), Thread.currentThread().isVirtual()));
        }
    }

    @Configuration @Profile("cron-auto-scan-test")
    static class TaskFactory {
        @Bean FactoryTask factoryTask() { return new FactoryTask(); }
    }

    public abstract static class ParentTask {
        final LinkedBlockingQueue<Fired> fired = new LinkedBlockingQueue<>();
        @Cron(value = "* * * * * ?", domain = 3, routeKey = 9L)
        public void inheritedTick() {
            var context = Contexts.current();
            fired.add(new Fired(context.routeDomain(), context.routeKey(), Thread.currentThread().isVirtual()));
        }
    }

    @Profile("cron-auto-scan-test")
    public static class InheritedTask extends ParentTask {}

    public interface DefaultTask {
        void save(Fired invocation);
        @Cron(value = "* * * * * ?", domain = 3, routeKey = 10L)
        default void defaultTick() {
            var context = Contexts.current();
            save(new Fired(context.routeDomain(), context.routeKey(), Thread.currentThread().isVirtual()));
        }
    }

    @Profile("cron-auto-scan-test")
    public static class InterfaceTask implements DefaultTask {
        final LinkedBlockingQueue<Fired> fired = new LinkedBlockingQueue<>();
        @Override public void save(Fired invocation) { fired.add(invocation); }
    }

    @Profile("invalid-cron-scan-test")
    public static class InvalidTask {
        @Cron(value = "* * * * * ?", domain = 3, routeKey = 9L)
        private void privateTick() { fail("Invalid cron executed"); }
    }

    @Test void scansMethodOnlyClassesAndRegistersFactoryBeansWithoutATypeList() throws Exception {
        try (var spring = new AnnotationConfigApplicationContext()) {
            spring.getEnvironment().setActiveProfiles("cron-auto-scan-test");
            spring.getEnvironment().getPropertySources().addFirst(new MapPropertySource("timer-test",
                    Map.of("game.demo.timer.delayMillis", "3600000")));
            spring.register(TestConfiguration.class, TaskFactory.class);
            spring.refresh();
            var scanned = spring.getBean(ScannedTask.class);
            var factory = spring.getBean(FactoryTask.class);
            var inherited = spring.getBean(InheritedTask.class);
            var interfaceTask = spring.getBean(InterfaceTask.class);
            assertEquals(new Fired(3, 7L, true), scanned.fired.poll(5, TimeUnit.SECONDS));
            assertEquals(new Fired(3, 8L, true), factory.fired.poll(5, TimeUnit.SECONDS));
            assertEquals(new Fired(3, 9L, true), inherited.fired.poll(5, TimeUnit.SECONDS));
            assertEquals(new Fired(3, 10L, true), interfaceTask.fired.poll(5, TimeUnit.SECONDS));
            var runtime = spring.getBean(GameRuntime.class);
            assertTrue(runtime.cron().cancel(ScannedTask.class, "tick"));
            assertTrue(runtime.cron().cancel(FactoryTask.class, "tick"));
            assertTrue(runtime.cron().cancel(ParentTask.class, "inheritedTick"));
            assertTrue(runtime.cron().cancel(DefaultTask.class, "defaultTick"));
        }
    }

    @Test void doesNotInstantiateUnrelatedBeansAndDeduplicatesTargetIdentity() {
        var beans = new DefaultListableBeanFactory();
        var unrelated = new RootBeanDefinition(Object.class);
        unrelated.setLazyInit(true);
        unrelated.setInstanceSupplier(() -> { throw new AssertionError("Unrelated Bean was initialized"); });
        beans.registerBeanDefinition("unrelated", unrelated);
        var task = new ScannedTask();
        beans.registerSingleton("task", task);
        beans.registerSingleton("sameTask", task);
        assertEquals(1, CronBeans.discover(beans).size());
        assertSame(task, CronBeans.discover(beans).getFirst());
        assertFalse(beans.containsSingleton("unrelated"));
    }

    @Test void rejectsPrototypeAndProxyTaskOwnersBeforeRuntimeBuild() {
        var beans = new DefaultListableBeanFactory();
        var prototype = new RootBeanDefinition(ScannedTask.class);
        prototype.setScope("prototype");
        beans.registerBeanDefinition("task", prototype);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> CronBeans.discover(beans))
                .getMessage().contains("singleton"));
        beans.removeBeanDefinition("task");
        var proxied = new RootBeanDefinition(ScannedTask.class);
        proxied.setInstanceSupplier(() -> {
            var proxy = new ProxyFactory(new ScannedTask());
            proxy.setProxyTargetClass(true);
            return proxy.getProxy();
        });
        beans.registerBeanDefinition("task", proxied);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> CronBeans.discover(beans))
                .getMessage().contains("proxies"));
    }

    @Test void invalidAnnotatedMethodsFailStartupInsteadOfBeingIgnored() {
        try (var spring = new AnnotationConfigApplicationContext()) {
            spring.getEnvironment().setActiveProfiles("invalid-cron-scan-test");
            spring.register(TestConfiguration.class);
            var failure = assertThrows(BeanCreationException.class, spring::refresh);
            assertInstanceOf(IllegalArgumentException.class, failure.getMostSpecificCause());
        }
    }
}
