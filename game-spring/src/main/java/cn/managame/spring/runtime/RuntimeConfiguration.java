package cn.managame.spring.runtime;

import cn.managame.runtime.*;
import cn.managame.runtime.event.*;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.http.HttpHandler;
import cn.managame.runtime.protocol.ProtocolProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.aop.support.AopUtils;
import java.lang.annotation.Annotation;
import java.util.*;

@Configuration(proxyBeanMethods = false)
class RuntimeConfiguration {
    @Bean(destroyMethod = "close")
    GameRuntime gameRuntime(ApplicationContext context, ConfigurableListableBeanFactory beans,
                            ObjectProvider<GameRuntimeConfigurer> configurers,
                            ObjectProvider<ProtocolProvider> protocols) {
        var builder = GameRuntimeBuilder.builder()
                .protocols(protocols.orderedStream().toList())
                .handlers(handlers(context, beans, Handler.class))
                .eventHandlers(handlers(context, beans, EventHandler.class))
                .httpHandlers(handlers(context, beans, HttpHandler.class))
                .cronHandlers(CronBeans.discover(beans));
        configurers.orderedStream().forEach(configurer -> configurer.configure(builder));
        var runtime = builder.build();
        try { Events.bind(runtime); return runtime; }
        catch (RuntimeException | Error failure) { runtime.close(); throw failure; }
    }

    @Bean RuntimeDrainListener runtimeDrainListener(GameRuntime runtime, ApplicationContext context) {
        return new RuntimeDrainListener(runtime, context);
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
