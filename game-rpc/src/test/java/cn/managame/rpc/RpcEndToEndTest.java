package cn.managame.rpc;

import cn.managame.rpc.connection.RpcConnection;
import cn.managame.rpc.support.StringTestSerializer;
import cn.managame.serialization.SerializationType;
import cn.managame.serialization.SerializerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 真实 loopback 端到端：建连 + 握手 + future/callback invoke + oneway + 超时。
 */
class RpcEndToEndTest {

    private static final byte SERIAL = SerializationType.JSON.typeId();
    private static final int CMD_ECHO = 1;
    private static final int CMD_DROP = 2;   // 服务端故意不回，触发超时
    private static final int CMD_ONEWAY = 3;

    private static final String SERVER_NAME = "game-server";
    private static final String SERVER_ID = "1";

    private RpcServer server;
    private RpcClient client;
    private final CountDownLatch onewayLatch = new CountDownLatch(1);

    static {
        // 测试用 String<->UTF8 序列化器占用 JSON 槽位，注册进进程级共享单例（RpcServer/RpcClient 直接取该单例）
        SerializerManager.getInstance().register(new StringTestSerializer());
    }

    @BeforeEach
    void setUp() throws Exception {
        int port = freePort();

        RpcMessageHandler serverHandler = new RpcMessageHandler() {
            @Override
            protected void handleUserMsg(RpcConnection connection, RpcRequest msg) {
                switch (msg.getCommand()) {
                    case CMD_ECHO -> {
                        String text = new String((byte[]) msg.getBody(), StandardCharsets.UTF_8);
                        byte[] body = ("echo:" + text).getBytes(StandardCharsets.UTF_8);
                        connection.writeMsg(RpcResponse.of(msg.getRequestId(), msg.getSerialType(), body));
                    }
                    case CMD_ONEWAY -> onewayLatch.countDown();
                    default -> {
                        // CMD_DROP: 不回包
                    }
                }
            }
        };
        server = new RpcServer(new RpcServerConfig().port(port), serverHandler);
        server.start();

        RpcMessageHandler clientHandler = new RpcMessageHandler() {
            @Override
            protected void handleUserMsg(RpcConnection connection, RpcRequest msg) {
                // 客户端不接收业务推送
            }
        };
        client = new RpcClient(new RpcClientConfig().serviceName("client-a").serviceId("1"),
                clientHandler);

        ConnectionTargetConfig target = new ConnectionTargetConfig();
        target.setServiceName(SERVER_NAME);
        target.setServiceId(SERVER_ID);
        target.setIp("127.0.0.1");
        target.setPort(port);
        client.connect(target);

        awaitConnected();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void futureInvokeEchoesBody() throws Exception {
        RpcRequest request = RpcRequest.of(CMD_ECHO).routeKey(1L).serialType(SERIAL).body("hello");
        RpcResponse response = client.invoke(SERVER_NAME, SERVER_ID, request).await(2000);
        assertTrue(response.isSuccess());
        assertEquals("echo:hello", new String((byte[]) response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void callbackInvokeDeserializesResponse() throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        RpcRequest request = RpcRequest.of(CMD_ECHO).routeKey(2L).serialType(SERIAL).body("world");
        client.invoke(SERVER_NAME, SERVER_ID, request, new RpcCallback<String>() {
            @Override
            public void onSuccess(String value) {
                result.set(value);
                latch.countDown();
            }

            @Override
            public void onException(Throwable e) {
                error.set(e);
                latch.countDown();
            }
        });
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("echo:world", result.get());
    }

    @Test
    void onewayReachesServer() throws Exception {
        client.oneway(SERVER_NAME, SERVER_ID,
                RpcRequest.oneway(CMD_ONEWAY).routeKey(3L).serialType(SERIAL).body("x"));
        assertTrue(onewayLatch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void invokeTimesOutWhenServerDrops() {
        RpcRequest request = RpcRequest.of(CMD_DROP).routeKey(4L).serialType(SERIAL).body("x").timeoutMillis(300);
        RpcFuture future = client.invoke(SERVER_NAME, SERVER_ID, request);
        GameRpcException ex = assertThrows(GameRpcException.class, () -> future.await(2000));
        assertTrue(ex.getMessage().contains("timeout"), ex.getMessage());
    }

    @Test
    void rejectedHandshakeKeepsConnectionOutOfPeer() throws Exception {
        int port = freePort();
        RpcServer secured = new RpcServer(new RpcServerConfig().port(port).authToken("secret"),
                new RpcMessageHandler() {
                    @Override
                    protected void handleUserMsg(RpcConnection connection, RpcRequest msg) {
                    }
                });
        secured.start();
        RpcClient badClient = new RpcClient(
                new RpcClientConfig().serviceName("client-b").serviceId("1"), // 没带 token
                new RpcMessageHandler() {
                    @Override
                    protected void handleUserMsg(RpcConnection connection, RpcRequest msg) {
                    }
                });
        try {
            ConnectionTargetConfig target = new ConnectionTargetConfig();
            target.setServiceName("secured-server");
            target.setServiceId("1");
            target.setIp("127.0.0.1");
            target.setPort(port);
            badClient.connect(target);

            // 留出握手往返时间：token 不符会被服务端断连，连接不应挂入 peer
            Thread.sleep(500);
            RpcPeer peer = badClient.getRpcPeer("secured-server", "1");
            assertNotNull(peer);
            assertEquals(0, peer.connectionCount());

            RpcFuture future = badClient.invoke("secured-server", "1",
                    RpcRequest.of(CMD_ECHO).routeKey(1L).serialType(SERIAL).body("x"));
            assertThrows(GameRpcException.class, () -> future.await(1000));
        } finally {
            badClient.close();
            secured.close();
        }
    }

    @Test
    void reconnectsAfterServerRestart() throws Exception {
        int port = freePort();
        RpcServer s1 = new RpcServer(new RpcServerConfig().port(port), echoHandler());
        s1.start();
        RpcClient c = new RpcClient(
                new RpcClientConfig().serviceName("client-r").serviceId("1")
                        .heartbeatIntervalSeconds(0).idleTimeoutSeconds(0) // 本测试只验重连
                        .reconnectInitialBackoffMillis(200).reconnectMaxBackoffMillis(500),
                noopHandler());
        try {
            c.connect(target("rsvr", "1", port));
            awaitReady(c, "rsvr", "1", 2000);
            assertEquals("echo:a", echo(c, "rsvr", "1", "a"));

            s1.close();
            awaitNoConnection(c, "rsvr", "1", 2000); // 客户端感知断开

            RpcServer s2 = new RpcServer(new RpcServerConfig().port(port), echoHandler());
            s2.start();
            try {
                awaitReady(c, "rsvr", "1", 5000); // 退避重连后重新挂上
                assertEquals("echo:b", echo(c, "rsvr", "1", "b"));
                Thread.sleep(800); // 已成功后不能再有同槽位的遗留重连任务
                assertEquals(1, c.getRpcPeer("rsvr", "1").connectionCount());
                assertEquals(1, c.getMetrics().reconnectSuccesses());
            } finally {
                s2.close();
            }
        } finally {
            c.close();
        }
    }

    @Test
    void heartbeatKeepsConnectionAlive() throws Exception {
        int port = freePort();
        // 服务端 3s 读空闲判死；客户端每 1s 发心跳应能保活
        RpcServer s = new RpcServer(new RpcServerConfig().port(port).idleTimeoutSeconds(3),
                echoHandler());
        s.start();
        RpcClient c = new RpcClient(
                new RpcClientConfig().serviceName("client-h").serviceId("1")
                        .heartbeatIntervalSeconds(1).idleTimeoutSeconds(10)
                        .reconnectEnabled(false), // 关重连：心跳若失效连接会死且不恢复，断言即失败
                noopHandler());
        try {
            c.connect(target("hsvr", "1", port));
            awaitReady(c, "hsvr", "1", 2000);
            Thread.sleep(4000); // 静默 4s（> 服务端 3s 读空闲），仅靠心跳维持
            assertEquals("echo:alive", echo(c, "hsvr", "1", "alive"));
            assertTrue(c.getRpcPeer("hsvr", "1").connectionCount() > 0);
        } finally {
            c.close();
            s.close();
        }
    }

    @Test
    void appliesConfiguredWriteBufferWaterMark() throws Exception {
        int port = freePort();
        RpcServer s = new RpcServer(new RpcServerConfig().port(port), echoHandler());
        s.start();
        RpcClient c = new RpcClient(new RpcClientConfig().serviceName("client-watermark").serviceId("1")
                .writeBufferWaterMark(8 * 1024, 32 * 1024), noopHandler());
        try {
            c.connect(target("watermark-server", "1", port));
            awaitReady(c, "watermark-server", "1", 2000);
            RpcConnection connection = c.getRpcPeer("watermark-server", "1").snapshot()[0];
            assertEquals(8 * 1024, connection.getChannel().config().getWriteBufferWaterMark().low());
            assertEquals(32 * 1024, connection.getChannel().config().getWriteBufferWaterMark().high());
        } finally {
            c.close();
            s.close();
        }
    }

    @Test
    void disconnectRemovesTargetAndStopsReconnect() throws Exception {
        int port = freePort();
        RpcServer s = new RpcServer(new RpcServerConfig().port(port), echoHandler());
        s.start();
        RpcClient c = new RpcClient(
                new RpcClientConfig().serviceName("client-d").serviceId("1")
                        .heartbeatIntervalSeconds(0).idleTimeoutSeconds(0)
                        .reconnectInitialBackoffMillis(100).reconnectMaxBackoffMillis(300),
                noopHandler());
        try {
            c.connect(target("dsvr", "1", port));
            awaitReady(c, "dsvr", "1", 2000);
            assertEquals("echo:a", echo(c, "dsvr", "1", "a"));

            c.disconnect("dsvr", "1");
            assertNull(c.getRpcPeer("dsvr", "1"));
            Thread.sleep(500); // 确认重连不会把已移除的目标拉回来
            assertNull(c.getRpcPeer("dsvr", "1"));

            c.connect(target("dsvr", "1", port)); // 再次加入应能重新建立
            awaitReady(c, "dsvr", "1", 2000);
            assertEquals("echo:b", echo(c, "dsvr", "1", "b"));
        } finally {
            c.close();
            s.close();
        }
    }

    @Test
    void broadcastReachesEachInstanceOfService() throws Exception {
        int p1 = freePort();
        int p2 = freePort();
        CountDownLatch latch = new CountDownLatch(2);
        RpcServer s1 = new RpcServer(new RpcServerConfig().port(p1), onewayCounter(latch));
        RpcServer s2 = new RpcServer(new RpcServerConfig().port(p2), onewayCounter(latch));
        s1.start();
        s2.start();
        RpcClient c = new RpcClient(new RpcClientConfig().serviceName("client-bc").serviceId("1"),
                noopHandler());
        try {
            c.connect(target("bsvr", "1", p1));
            c.connect(target("bsvr", "2", p2));
            awaitReady(c, "bsvr", "1", 2000);
            awaitReady(c, "bsvr", "2", 2000);

            c.broadcast("bsvr", RpcRequest.oneway(CMD_ONEWAY).routeKey(0L).serialType(SERIAL).body("ping"));
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } finally {
            c.close();
            s1.close();
            s2.close();
        }
    }

    @Test
    void serverHandlerExceptionReturnsErrorResponse() throws Exception {
        int port = freePort();
        RpcServer s = new RpcServer(new RpcServerConfig().port(port), new RpcMessageHandler() {
            @Override
            protected void handleUserMsg(RpcConnection connection, RpcRequest msg) {
                throw new RuntimeException("boom");
            }
        });
        s.start();
        RpcClient c = new RpcClient(new RpcClientConfig().serviceName("client-e").serviceId("1"),
                noopHandler());
        try {
            c.connect(target("esvr", "1", port));
            awaitReady(c, "esvr", "1", 2000);
            RpcResponse resp = c.invoke("esvr", "1",
                    RpcRequest.of(CMD_ECHO).routeKey(1L).serialType(SERIAL).body("x")).await(2000);
            assertFalse(resp.isSuccess()); // 收到错误响应而不是等到超时
            assertEquals(RpcMessageHandler.CODE_SERVER_ERROR, resp.code());
        } finally {
            c.close();
            s.close();
        }
    }

    private RpcMessageHandler onewayCounter(CountDownLatch latch) {
        return new RpcMessageHandler() {
            @Override
            protected void handleUserMsg(RpcConnection connection, RpcRequest msg) {
                if (msg.getCommand() == CMD_ONEWAY) {
                    latch.countDown();
                }
            }
        };
    }

    @Test
    void closeDrainsInflightRequest() throws Exception {
        int port = freePort();
        RpcServer s = new RpcServer(new RpcServerConfig().port(port),
                new RpcMessageHandler() {
                    @Override
                    protected void handleUserMsg(RpcConnection connection, RpcRequest msg) {
                        if (msg.getCommand() == CMD_ECHO) {
                            String text = new String((byte[]) msg.getBody(), StandardCharsets.UTF_8);
                            // 延迟回包：切到别的线程 sleep 后再写（不能阻塞 IO 线程）
                            new Thread(() -> {
                                try {
                                    Thread.sleep(300);
                                } catch (InterruptedException ignored) {
                                    return;
                                }
                                connection.writeMsg(RpcResponse.of(msg.getRequestId(), msg.getSerialType(),
                                        ("echo:" + text).getBytes(StandardCharsets.UTF_8)));
                            }).start();
                        }
                    }
                });
        s.start();
        RpcClient c = new RpcClient(new RpcClientConfig().serviceName("d").serviceId("1"),
                noopHandler());
        try {
            c.connect(target("dsvr", "1", port));
            awaitReady(c, "dsvr", "1", 2000);
            RpcFuture f = c.invoke("dsvr", "1",
                    RpcRequest.of(CMD_ECHO).routeKey(1L).serialType(SERIAL).body("x"));
            c.close(2000); // 应等到延迟响应回来再真正关
            assertTrue(f.isDone());
            assertTrue(f.isSuccess());
            assertEquals("echo:x", new String((byte[]) f.getResponse().body(), StandardCharsets.UTF_8));
        } finally {
            s.close();
        }
    }

    @Test
    void closeGivesUpAfterGraceWhenNoResponse() throws Exception {
        int port = freePort();
        RpcServer s = new RpcServer(new RpcServerConfig().port(port), noopHandler()); // 不回包
        s.start();
        RpcClient c = new RpcClient(new RpcClientConfig().serviceName("d2").serviceId("1"),
                noopHandler());
        try {
            c.connect(target("dsvr2", "1", port));
            awaitReady(c, "dsvr2", "1", 2000);
            RpcFuture f = c.invoke("dsvr2", "1",
                    RpcRequest.of(CMD_ECHO).routeKey(1L).serialType(SERIAL).timeoutMillis(30_000).body("x"));
            long t0 = System.currentTimeMillis();
            c.close(300); // 在途永不回，应在 ~300ms 后放弃并关连接
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue(elapsed >= 250 && elapsed < 2000, "elapsed=" + elapsed);
            assertTrue(f.isDone());     // 关连接时被失败
            assertFalse(f.isSuccess());
        } finally {
            s.close();
        }
    }

    private RpcMessageHandler echoHandler() {
        return new RpcMessageHandler() {
            @Override
            protected void handleUserMsg(RpcConnection connection, RpcRequest msg) {
                if (msg.getCommand() == CMD_ECHO) {
                    String text = new String((byte[]) msg.getBody(), StandardCharsets.UTF_8);
                    connection.writeMsg(RpcResponse.of(msg.getRequestId(), msg.getSerialType(),
                            ("echo:" + text).getBytes(StandardCharsets.UTF_8)));
                }
            }
        };
    }

    private RpcMessageHandler noopHandler() {
        return new RpcMessageHandler() {
            @Override
            protected void handleUserMsg(RpcConnection connection, RpcRequest msg) {
            }
        };
    }

    private ConnectionTargetConfig target(String name, String id, int port) {
        ConnectionTargetConfig t = new ConnectionTargetConfig();
        t.setServiceName(name);
        t.setServiceId(id);
        t.setIp("127.0.0.1");
        t.setPort(port);
        return t;
    }

    private String echo(RpcClient c, String name, String id, String text) throws Exception {
        RpcResponse response = c.invoke(name, id,
                RpcRequest.of(CMD_ECHO).routeKey(1L).serialType(SERIAL).body(text)).await(2000);
        return new String((byte[]) response.body(), StandardCharsets.UTF_8);
    }

    private void awaitReady(RpcClient c, String name, String id, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            RpcPeer peer = c.getRpcPeer(name, id);
            if (peer != null && peer.connectionCount() > 0) {
                return;
            }
            Thread.sleep(20);
        }
        fail("client not ready in time");
    }

    private void awaitNoConnection(RpcClient c, String name, String id, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            RpcPeer peer = c.getRpcPeer(name, id);
            if (peer == null || peer.connectionCount() == 0) {
                return;
            }
            Thread.sleep(20);
        }
        fail("connection did not drop in time");
    }

    private void awaitConnected() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            RpcPeer peer = client.getRpcPeer(SERVER_NAME, SERVER_ID);
            if (peer != null && peer.connectionCount() > 0) {
                return;
            }
            Thread.sleep(10);
        }
        fail("client did not connect in time");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
