package cn.managame.demo.common.runtime;

import cn.managame.demo.common.protocol.GameProtocols;
import cn.managame.demo.bus.system.DemoTasks;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.event.EventHandler;
import cn.managame.runtime.event.Events;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.http.HttpHandler;
import cn.managame.runtime.timer.TimerRef;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.executor.RouteExecutors;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.util.List;
import java.util.Arrays;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.core.env.Environment;

@Configuration
@Import(GameProtocols.class)
@ComponentScan(basePackages = "cn.managame.demo", useDefaultFilters = false,
        includeFilters = {
                @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = {EventHandler.class, Handler.class, HttpHandler.class}),
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = CronMethodFilter.class)
        })
public class GameRuntimeConfig {
    @Bean(destroyMethod = "close")
    public GameRuntime gameRuntime(ApplicationContext context, GameProtocols protocols, ConfigurableListableBeanFactory beans) {
        var handlers = context.getBeansWithAnnotation(EventHandler.class).values();
        var executor = RouteExecutors.virtualThreads();
        try {
            GameRuntime runtime = GameRuntimeBuilder.builder()
                    .routeDomains(Arrays.stream(GameDomain.values()).map(GameDomain::routeDomain).toList())
                    .routeExecutors(List.of(RouteExecutorBinding.of(executor,
                            Arrays.stream(GameDomain.values()).mapToInt(GameDomain::id).toArray())))
                    .protocols(List.of(protocols))
                    .handlers(context.getBeansWithAnnotation(Handler.class).values())
                    .handlerContextFactory((domain, connection, message) ->
                            GameDomain.fromId(domain).handlerContext(connection, message))
                    .eventHandlers(handlers)
                    .httpHandlers(context.getBeansWithAnnotation(HttpHandler.class).values())
                    .cronHandlers(CronBeans.discover(beans))
                    .cronZone(ZoneId.of("UTC"))
                    .build();
            try {
                Events.bind(runtime);
                return runtime;
            } catch (RuntimeException | Error failure) {
                runtime.close();
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            executor.close();
            throw failure;
        }
    }

    @Bean(destroyMethod = "cancel")
    public TimerRef demoStartupTimer(GameRuntime runtime, DemoTasks tasks, Environment environment) {
        long delayMillis = environment.getProperty("game.demo.timer.delayMillis", Long.class, 3000L);
        return runtime.timer().schedule(GameDomain.SYSTEM_ID, DemoTasks.ROUTE_KEY,
                Duration.ofMillis(delayMillis), tasks::onTimer);
    }
}
