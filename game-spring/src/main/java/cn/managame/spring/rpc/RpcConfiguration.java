package cn.managame.spring.rpc;

import cn.managame.rpc.node.RpcNode;
import cn.managame.runtime.GameRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.net.InetSocketAddress;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class RpcConfiguration {
    @Bean(destroyMethod = "close")
    GameRpc gameRpc(GameRuntime runtime, GameRpcCodec codec, Environment environment,
                    ObjectProvider<GameRpcConfigurer> configurers) {
        int id = environment.getRequiredProperty("game.rpc.node-id", Integer.class);
        int port = environment.getRequiredProperty("game.rpc.port", Integer.class);
        if (port < 0 || port > 65535) throw new IllegalArgumentException("game.rpc.port must be 0..65535");
        String host = environment.getProperty("game.rpc.bind-address", "127.0.0.1");
        if (host.isBlank()) throw new IllegalArgumentException("game.rpc.bind-address must be nonblank");
        var builder = RpcNode.builder().nodeId(id).bindAddress(new InetSocketAddress(host, port));
        Long timeout = environment.getProperty("game.rpc.call-timeout-millis", Long.class);
        if (timeout != null) builder.callTimeout(Duration.ofMillis(timeout));
        configurers.orderedStream().forEach(configurer -> configurer.configure(builder));
        return new GameRpc(runtime, codec, builder);
    }

    @Bean(destroyMethod = "") RpcNode gameRpcNode(GameRpc rpc) { return rpc.node(); }
    @Bean RpcNodeLifecycle gameRpcLifecycle(GameRpc rpc) { return new RpcNodeLifecycle(rpc.node()); }
}
