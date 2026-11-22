package cn.managame.example.world;

import cn.managame.example.DomainConstant;
import cn.managame.example.Protocols;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.dispatch.Dispatcher;
import cn.managame.runtime.executor.RouteExecutors;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 世界服的装配：只注册 WORLD 域与 Dispatcher，不含客户端网关和玩家数据库。 */
@Configuration
public class WorldServerConfig {

    @Bean
    public Protocols protocols() { return Protocols.game(); }

    @Bean
    public GameRuntime gameRuntime() {
        return GameRuntime.builder()
                .domain(DomainConstant.WORLD, RouteExecutors.virtualThreads(), RouteKeys.constant(0))
                .build();
    }

    @Bean
    public Dispatcher dispatcher(GameRuntime gameRuntime, ApplicationContext context) {
        return Dispatcher.builder(gameRuntime).register(context.getBeansWithAnnotation(Domain.class).values()).build();
    }
}
