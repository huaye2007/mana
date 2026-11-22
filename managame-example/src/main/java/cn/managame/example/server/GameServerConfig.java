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
import org.springframework.core.env.Environment;

/**
 * 游戏服的装配：GameRuntime（只注册 PLAYER 和 LOGIN 域）、Dispatcher，以及客户端 TCP 网关。
 * Spring 按依赖的反序关闭：先停客户端服务器与 RPC/AI，再关 GameRuntime 等已接受的任务执行完，最后关 GameData 落盘。
 */
@Configuration
public class GameServerConfig {

    @Bean
    public Protocols protocols() { return Protocols.game(); }

    /** 依赖 gameData：GameRuntime 先关，业务任务执行完之后 GameData 才关。 */
    @Bean
    @DependsOn("gameData")
    public GameRuntime gameRuntime() {
        return GameRuntime.builder()
                .domain(DomainConstant.PLAYER, RouteExecutors.virtualThreads(), RouteKeys.businessId())
                .domain(DomainConstant.LOGIN, RouteExecutors.virtualThreads())   // 登录与账号查询按 userId 路由
                .build();
    }

    /** 注册当前进程里所有标了 @Domain 的 bean 中的 @HandleMethod。 */
    @Bean
    public Dispatcher dispatcher(GameRuntime gameRuntime, ApplicationContext context) {
        return Dispatcher.builder(gameRuntime).register(context.getBeansWithAnnotation(Domain.class).values()).build();
    }

    @Bean
    public NetworkServer gameServer(Dispatcher dispatcher, Protocols protocols, Environment env) {
        int port = env.getProperty("game.port", Integer.class, 7001);
        return NetworkServer.start(port, ConnectionInitializer.tcp()
                .addLast(NetworkHandlers.lengthFieldFraming(GamePacketCodec.MAX_FRAME_LENGTH))
                .addLast("game-codec", new GamePacketCodec(protocols))
                .handler(new GameGateway(dispatcher, protocols))
                .build());
    }
}
