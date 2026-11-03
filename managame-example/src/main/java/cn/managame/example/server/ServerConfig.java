package cn.managame.example.server;

import cn.managame.example.DomainConstant;
import cn.managame.example.GamePacketCodec;
import cn.managame.example.Protocols;
import cn.managame.network.netty.ConnectionInitializer;
import cn.managame.network.netty.NetworkHandlers;
import cn.managame.network.netty.NetworkServer;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.dispatch.Dispatcher;
import cn.managame.runtime.executor.RouteExecutors;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * 服务器的装配：GameRuntime、Dispatcher，以及基于 network 的 TCP 服务器（长度帧 + GamePacketCodec + GameGateway）。
 * Spring 按依赖的反序关闭：先停服务器不再收消息，再关 Dispatcher，最后 GameRuntime 等已接受的任务执行完。
 */
@Configuration
public class ServerConfig {
    /** 客户端连接的端口，-Dgame.port 指定，默认 7001。 */
    private static final int PORT = Integer.getInteger("game.port", 7001);

    @Bean
    public Protocols protocols() { return Protocols.game(); }

    /** 依赖 gameData：GameRuntime 先关，业务任务执行完之后 GameData 才关。 */
    @Bean
    @DependsOn("gameData")
    public GameRuntime gameRuntime() {
        return GameRuntime.builder()
                .domain(DomainConstant.PLAYER, RouteExecutors.virtualThreads(), RouteKeys.businessId())
                .domain(DomainConstant.LOGIN, RouteExecutors.virtualThreads())   // 登录处理方法用 routeKey = "userId"
                .build();
    }

    /** 注册所有标了 @Domain 的 bean 里的 @HandleMethod。 */
    @Bean
    public Dispatcher dispatcher(GameRuntime gameRuntime, ApplicationContext context) {
        return Dispatcher.builder(gameRuntime).register(context.getBeansWithAnnotation(Domain.class).values()).build();
    }

    @Bean
    public NetworkServer gameServer(Dispatcher dispatcher, Protocols protocols) {
        return NetworkServer.start(PORT, ConnectionInitializer.tcp()
                .addLast(NetworkHandlers.lengthFieldFraming(GamePacketCodec.MAX_FRAME_LENGTH))
                .addLast("game-codec", new GamePacketCodec(protocols))
                .handler(new GameGateway(dispatcher, protocols))
                .build());
    }
}
