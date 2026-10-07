package cn.managame.demo;

import cn.managame.demo.bus.user.LoginReq;
import cn.managame.demo.common.rpc.GameRpcConfig;
import cn.managame.demo.common.runtime.GameRuntimeConfig;
import cn.managame.demo.examples.ExampleTestSupport;
import cn.managame.demo.network.message.PingMessage;
import cn.managame.rpc.node.RpcNode;
import cn.managame.spring.rpc.GameRpc;
import cn.managame.spring.rpc.GameRpcCodec;
import org.apache.fory.ThreadSafeFory;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DemoRpcBootstrapTest extends ExampleTestSupport {
    @Test void springStartsRpcWithTheSharedForyCodecAndClosesItsListener() throws Exception {
        InetSocketAddress address;
        RpcNode node;
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("rpc-test", Map.of(
                    "game.rpc.node-id", 101, "game.rpc.port", 0,
                    "game.http.enabled", false, "game.demo.timer.delayMillis", 3600000)));
            context.register(GameRuntimeConfig.class, GameRpcConfig.class);
            context.refresh();
            assertNotNull(context.getBean(GameRpc.class));
            node = context.getBean(RpcNode.class);
            address = (InetSocketAddress) node.localAddress();
            assertNotNull(address);
            assertTrue(address.getPort() > 0);
            var fory = context.getBean(ThreadSafeFory.class);
            var codec = context.getBean(GameRpcCodec.class);
            var message = new PingMessage(1234L);
            assertEquals(message, codec.decode(fory.serialize(message), PingMessage.class));
            assertEquals(message, fory.deserialize(codec.encode(message), PingMessage.class));
            assertThrows(ClassCastException.class, () -> codec.decode(codec.encode(message), LoginReq.class));
            assertThrows(RuntimeException.class, () -> codec.decode(new byte[0], PingMessage.class));
            // Node is already RUNNING; topology registration requires no manual start.
            node.removePeer(202);
        }
        assertThrows(IllegalStateException.class, node::start);
        try (var socket = new ServerSocket()) { socket.bind(address); }
    }
}
