package cn.managame.demo.common.rpc;

import cn.managame.demo.common.serialization.ForyConfig;
import cn.managame.spring.rpc.EnableGameRpc;
import cn.managame.spring.rpc.GameRpcCodec;
import org.apache.fory.ThreadSafeFory;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Optional managed RPC for the demo. Enabled only when game.rpc.node-id is present before refresh,
 * so the scanned main application keeps running without RPC properties.
 * Topology stays application policy: call RpcNode.addPeer after refresh.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(GameRpcConfig.Enabled.class)
@EnableGameRpc
@Import(ForyConfig.class)
public class GameRpcConfig {
    @Bean
    public GameRpcCodec gameRpcCodec(ThreadSafeFory fory) {
        return new GameRpcCodec() {
            @Override public byte[] encode(Object message) { return fory.serialize(message); }
            // Untyped decode plus an exact cast rejects a registered object of another type.
            @Override public <T> T decode(byte[] body, Class<T> type) { return type.cast(fory.deserialize(body)); }
        };
    }

    static final class Enabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getEnvironment().containsProperty("game.rpc.node-id");
        }
    }
}
