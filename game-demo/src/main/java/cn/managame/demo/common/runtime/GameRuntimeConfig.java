package cn.managame.demo.common.runtime;

import cn.managame.demo.bus.role.RoleId;
import cn.managame.demo.common.protocol.GameProtocols;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.event.EventHandler;
import cn.managame.runtime.event.Events;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerArgumentBinding;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.executor.RouteExecutors;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Arrays;

@Configuration
@Import(GameProtocols.class)
@ComponentScan(basePackages = "cn.managame.demo", useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = {EventHandler.class, Handler.class}))
public class GameRuntimeConfig {
    @Bean(destroyMethod = "close")
    public GameRuntime gameRuntime(ApplicationContext context, GameProtocols protocols) {
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
                    .handlerArguments(List.of(HandlerArgumentBinding.of(RoleId.class, RoleId::from)))
                    .eventHandlers(handlers)
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
}
