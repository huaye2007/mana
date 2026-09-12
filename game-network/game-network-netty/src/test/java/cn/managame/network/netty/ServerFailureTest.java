package cn.managame.network.netty;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.*;

import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Timeout(20)
class ServerFailureTest {
    @Test
    void channelCreationFailureRollsBackAndAllowsRepeatedStopForEveryServer() throws Exception {
        for (String protocol : new String[] {"tcp", "ws", "http"}) {
            for (int failAt : new int[] {1, 2}) {
                var cause = new IllegalStateException("Simulated channel creation failure");
                var creations = new AtomicInteger();
                try (var resources =
                        NetworkResources.builder()
                                .ioThreads(1)
                                .httpThreads(1)
                                .transport(
                                        NioIoHandler.newFactory(),
                                        () -> {
                                            if (creations.incrementAndGet() == failAt) throw cause;
                                            return new NioServerSocketChannel();
                                        },
                                        NioSocketChannel::new,
                                        NioDatagramChannel::new)
                                .build()) {
                    var local = new InetSocketAddress("127.0.0.1", 0);
                    NetworkServer server =
                            switch (protocol) {
                                case "tcp" ->
                                        TcpNetworkServer.builder()
                                                .resources(resources)
                                                .listen("first", local)
                                                .listen("second", local)
                                                .handlerFactory(() -> (c, m) -> {})
                                                .build();
                                case "ws" ->
                                        WsNetworkServer.builder()
                                                .resources(resources)
                                                .listen("first", local)
                                                .listen("second", local)
                                                .handlerFactory(() -> (c, m) -> {})
                                                .build();
                                default ->
                                        HttpNetworkServer.builder()
                                                .resources(resources)
                                                .listen("first", local)
                                                .listen("second", local)
                                                .build();
                            };
                    var failure = assertThrows(NetworkException.class, server::start);
                    assertSame(cause, failure.getCause());
                    assertEquals(0, failure.getSuppressed().length, protocol);
                    assertDoesNotThrow(server::stop);
                    assertDoesNotThrow(server::stop);
                    Map<String, InetSocketAddress> bound =
                            switch (server) {
                                case TcpNetworkServer tcp -> tcp.boundAddresses();
                                case WsNetworkServer ws -> ws.boundAddresses();
                                case HttpNetworkServer http -> http.boundAddresses();
                                default -> throw new AssertionError();
                            };
                    assertEquals(failAt - 1, bound.size());
                    for (var address : bound.values()) {
                        try (var socket = new ServerSocket()) {
                            socket.setReuseAddress(true);
                            socket.bind(address);
                        }
                    }
                    assertFalse(resources.isClosed());
                    assertEquals(7, resources.io().submit(() -> 7).sync().getNow());
                }
            }
        }
    }
}
