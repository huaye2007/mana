package cn.managame.demo.common.runtime;

import cn.managame.demo.common.protocol.GameProtocols;
import cn.managame.demo.common.serialization.ForyConfig;
import cn.managame.demo.network.GamePacketResultCodec;
import cn.managame.demo.bus.system.DemoTasks;
import cn.managame.runtime.protocol.ProtocolRegistry;
import org.apache.fory.ThreadSafeFory;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.executor.*;
import cn.managame.runtime.handler.ClientResultCodec;
import cn.managame.runtime.timer.TimerRef;
import cn.managame.spring.runtime.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Arrays;
import java.time.Duration;
import java.time.ZoneId;

@Configuration
@Import({GameProtocols.class, ForyConfig.class})
@EnableGameRuntime(basePackages = "cn.managame.demo")
@PropertySource("classpath:game-http.properties")
public class GameRuntimeConfig {
    @Bean(destroyMethod = "close")
    public RouteExecutor routeExecutor() { return RouteExecutors.virtualThreads(); }

    /** The packet wire format for business responses; every Handler that answers goes through it. */
    @Bean public ClientResultCodec clientResultCodec(ThreadSafeFory fory, ProtocolRegistry protocols) {
        return new GamePacketResultCodec(fory, protocols);
    }

    @Bean public GameRuntimeConfigurer runtimeConfigurer(RouteExecutor executor, ClientResultCodec clientResults) {
        return builder -> builder
                .routeDomains(Arrays.stream(GameDomain.values()).map(GameDomain::routeDomain).toList())
                .routeExecutors(List.of(RouteExecutorBinding.of(executor,
                        Arrays.stream(GameDomain.values()).mapToInt(GameDomain::id).toArray())))
                .handlerContextFactory((domain, command, requestId, connection, metadata, message) ->
                        GameDomain.fromId(domain).handlerContext(command, requestId, connection, metadata, message))
                .clientResultCodec(clientResults)
                .cronZone(ZoneId.of("UTC"));
    }

    @Bean(destroyMethod = "cancel")
    public TimerRef demoStartupTimer(GameRuntime runtime, DemoTasks tasks, Environment environment) {
        long delayMillis = environment.getProperty("game.demo.timer.delayMillis", Long.class, 3000L);
        return runtime.timer().schedule(GameDomain.SYSTEM_ID, DemoTasks.ROUTE_KEY,
                Duration.ofMillis(delayMillis), tasks::onTimer);
    }
}
