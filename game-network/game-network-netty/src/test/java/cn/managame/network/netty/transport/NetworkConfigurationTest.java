package cn.managame.network.netty.transport;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.NetworkException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Timeout(10)
class NetworkConfigurationTest {
    @Test
    void buildRejectsOptionsThatTheComponentCannotApply() {
        var tcpServer =
                TcpNetworkServer.builder()
                        .listen("127.0.0.1", 0)
                        .handlerFactory(() -> (c, m) -> {})
                        .option(NetworkOptions.WS_AGGREGATION, 1024);
        assertTrue(
                assertThrows(IllegalArgumentException.class, tcpServer::build)
                        .getMessage()
                        .contains("WS_AGGREGATION"));
        var tcpClient =
                TcpNetworkClient.builder()
                        .handlerFactory(() -> (c, m) -> {})
                        .option(NetworkOptions.START_TIMEOUT, Duration.ofSeconds(1));
        assertTrue(
                assertThrows(IllegalArgumentException.class, tcpClient::build)
                        .getMessage()
                        .contains("START_TIMEOUT"));
        var http =
                HttpNetworkServer.builder()
                        .listen("127.0.0.1", 0)
                        .option(NetworkOptions.READ_IDLE, Duration.ofSeconds(1));
        assertTrue(
                assertThrows(IllegalArgumentException.class, http::build)
                        .getMessage()
                        .contains("READ_IDLE"));
        var wsServer =
                WsNetworkServer.builder()
                        .listen("127.0.0.1", 0)
                        .handlerFactory(() -> (c, m) -> {})
                        .option(NetworkOptions.DESTROY_TIMEOUT, Duration.ofSeconds(1));
        assertTrue(
                assertThrows(IllegalArgumentException.class, wsServer::build)
                        .getMessage()
                        .contains("DESTROY_TIMEOUT"));
        var wsClient =
                WsNetworkClient.builder()
                        .handlerFactory(() -> (c, m) -> {})
                        .option(NetworkOptions.STOP_TIMEOUT, Duration.ofSeconds(1));
        assertTrue(
                assertThrows(IllegalArgumentException.class, wsClient::build)
                        .getMessage()
                        .contains("STOP_TIMEOUT"));
    }

    @Test
    void configuredResourceShutdownCanTimeOutAndBeRetried() throws Exception {
        var resources =
                NetworkResources.builder()
                        .ioThreads(1)
                        .shutdownTimeout(Duration.ofMillis(50))
                        .build();
        var group = resources.io();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        group.execute(
                () -> {
                    entered.countDown();
                    try {
                        release.await(8, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long before = System.nanoTime();
            assertThrows(NetworkException.class, resources::close);
            assertTrue(
                    System.nanoTime() - before < Duration.ofSeconds(2).toNanos(),
                    "close() must use the configured 50 ms wait, not the five-second default");
            assertTrue(resources.isClosed());
            assertFalse(group.isTerminated());
        } finally {
            release.countDown();
            resources.close(Duration.ofSeconds(5));
        }
        assertTrue(group.isTerminated());
    }

    @Test
    void invalidShutdownTimeoutDoesNotCloseResources() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkResources.builder().shutdownTimeout(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkResources.builder().shutdownTimeout(Duration.ofSeconds(-1)));
        try (var resources = NetworkResources.create()) {
            assertThrows(IllegalArgumentException.class, () -> resources.close(Duration.ZERO));
            assertFalse(resources.isClosed());
        }
    }
}
