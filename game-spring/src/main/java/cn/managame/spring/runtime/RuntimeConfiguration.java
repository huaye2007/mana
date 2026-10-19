package cn.managame.spring.runtime;

import cn.managame.boot.lifecycle.ShutdownBudget;
import cn.managame.boot.metrics.ReadinessCheck;
import cn.managame.runtime.*;
import cn.managame.runtime.event.*;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.http.HttpHandler;
import cn.managame.runtime.protocol.ProtocolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import java.lang.annotation.Annotation;
import java.util.*;

@Configuration(proxyBeanMethods = false)
@Import({RuntimeHttpConfiguration.class, cn.managame.spring.lifecycle.ShutdownConfiguration.class})
class RuntimeConfiguration {
    @Bean(destroyMethod = "close")
    GameRuntime gameRuntime(ApplicationContext context, ConfigurableListableBeanFactory beans,
                            ObjectProvider<GameRuntimeConfigurer> configurers,
                            ObjectProvider<ProtocolRegistry> protocols) {
        var builder = GameRuntimeBuilder.builder()
                .protocols(ProtocolRegistry.of(protocols.orderedStream().flatMap(r -> r.descriptors().stream()).toList()))
                .handlers(handlers(context, beans, Handler.class))
                .eventHandlers(handlers(context, beans, EventHandler.class))
                .httpHandlers(handlers(context, beans, HttpHandler.class))
                .cronHandlers(CronBeans.discover(beans));
        configurers.orderedStream().forEach(configurer -> configurer.configure(builder));
        // Deliberately not bound as the process default here. The binding lasts as long as the process
        // (RT-EVENT-05) while a bean factory runs once per context, so binding here would make a second
        // context in one JVM — every test suite — collide with the first. The application binds at startup.
        return builder.build();
    }

    @Bean RuntimeDrainListener runtimeDrainListener(GameRuntime runtime, ApplicationContext context,
                                                   ShutdownBudget budget) {
        return new RuntimeDrainListener(runtime, context, budget);
    }

    /**
     * The one readiness condition this integration owns, contributed as an ordinary Bean so the metrics
     * configuration needs no shared state type to read it. Ordered first: an instance leaving rotation
     * should report that rather than a consequence of it, and this is the only condition that turns false
     * while the instance is still admitting work.
     */
    @Bean @Order(Ordered.HIGHEST_PRECEDENCE)
    ReadinessCheck gameRotationReadiness(RuntimeDrainListener runtimeDrainListener) {
        return new ReadinessCheck() {
            @Override public String name() { return "shutdown.rotation"; }
            @Override public boolean ready() { return !runtimeDrainListener.leavingRotation(); }
        };
    }

    private static List<Object> handlers(ApplicationContext context, ConfigurableListableBeanFactory beans,
                                         Class<? extends Annotation> annotation) {
        var targets = new ArrayList<Object>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (String name : context.getBeanNamesForAnnotation(annotation)) {
            if (!beans.isSingleton(name)) throw new IllegalArgumentException("Runtime Handler must be singleton: " + name);
            Object target = context.getBean(name);
            if (AopUtils.isAopProxy(target)) throw new IllegalArgumentException("Runtime Handler proxies are not supported: " + name);
            if (seen.add(target)) targets.add(target);
        }
        return targets;
    }
}
