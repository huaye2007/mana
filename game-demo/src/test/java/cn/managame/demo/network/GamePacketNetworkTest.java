package cn.managame.demo.network;

import cn.managame.demo.common.serialization.ForyConfig;
import cn.managame.demo.common.protocol.GameProtocols;
import cn.managame.demo.common.runtime.GameRuntimeConfig;
import cn.managame.demo.common.runtime.GameDomain;
import cn.managame.demo.network.message.PingMessage;
import cn.managame.demo.bus.role.RoleHandler;
import cn.managame.demo.bus.user.LoginReq;
import cn.managame.demo.bus.user.UserHandler;
import cn.managame.runtime.context.ClientHandlerContext;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.netty.NetworkClient;
import cn.managame.network.netty.NetworkServer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GamePacketNetworkTest {
    record Received(Object message, long roleId, ClientHandlerContext context, boolean virtual) {}

    @Profile("packet-probe-only")
    static class LoginProbe extends UserHandler {
        final LinkedBlockingQueue<Received> received = new LinkedBlockingQueue<>();
        @Override @HandlerMethod(domain = GameDomain.LOGIN_ID) public void login(ClientHandlerContext context, LoginReq request) {
            assertNull(context.connection().get(GameSession.KEY));
            // Test-only token verifier. Production authentication remains business-owned.
            if ("demo-token".equals(request.getToken())) {
                context.connection().set(GameSession.KEY, new GameSession(99L, 20002L));
            }
            received.add(new Received(request, context.businessId(), context, Thread.currentThread().isVirtual()));
        }
    }

    @Profile("packet-probe-only")
    static class RoleProbe extends RoleHandler {
        final LinkedBlockingQueue<Received> received = new LinkedBlockingQueue<>();
        @Override @HandlerMethod public void ping(ClientHandlerContext context, PingMessage request) {
            received.add(new Received(request, context.businessId(), context, Thread.currentThread().isVirtual()));
        }
    }

    static AnnotationConfigApplicationContext context(LoginProbe login, RoleProbe role) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean("userHandler", UserHandler.class, () -> login);
        context.registerBean("roleHandler", RoleHandler.class, () -> role);
        context.register(ForyConfig.class, GameRuntimeConfig.class, GamePacketHandler.class);
        context.refresh(); return context;
    }
    @BeforeAll static void environment() throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            // Match game-network tests: avoid the host's intermittent AF_UNIX Selector pipe failure.
            Path marker = Path.of("target/packet-tcp-pipe-only").toAbsolutePath();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "Test-only: use TCP for the Selector wakeup pipe");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
    }

    @Test void decodedPacketMessagesReachTheirHandlerMethodsOverRealTcp() throws Exception {
        var responses = new LinkedBlockingQueue<GamePacket>();
        var disconnected = new CompletableFuture<Void>();
        var responseCount = new AtomicInteger();
        var connections = new ConcurrentLinkedQueue<Connection>();
        var boss = new NioEventLoopGroup(1);
        var worker = new NioEventLoopGroup(1);
        var loginProbe = new LoginProbe();
        var roleProbe = new RoleProbe();
        ConnectionHandler receiver = new Handler() {
            @Override public void onMessage(Connection connection, Object message) {
                responseCount.incrementAndGet();
                responses.add((GamePacket) message);
            }
            @Override public void onDisconnected(Connection connection) { disconnected.complete(null); }
            @Override public void onException(Connection connection, Throwable cause) {
                connection.close();
            }
        };
        try (var context = context(loginProbe, roleProbe);
             var server = NetworkServer.builder().bindAddress(new InetSocketAddress("127.0.0.1", 0))
                .bossGroup(boss).workerGroup(worker).pipeline(GamePacketNetworkTest::codecs)
                .handler(context.getBean(GamePacketHandler.class)).build();
             var client = NetworkClient.builder().eventLoopGroup(worker)
                     .pipeline(GamePacketNetworkTest::codecs).handler(receiver).build()) {
            server.start();
            Connection connection = client.connect(server.localAddress());
            connections.add(connection);
            var fory = new ForyConfig().fory(new GameProtocols());
            var body = new LoginReq(); body.setUserId(10001L); body.setServerId(2); body.setToken("invalid-token");
            assertEquals(WriteStatus.ACCEPTED, connection.write(
                    GamePacketCodecTest.packet(1003, 41, 0, fory.serialize(body))));
            Received rejected = loginProbe.received.poll(5, TimeUnit.SECONDS);
            assertNotNull(rejected);
            assertNull(rejected.context().connection().get(GameSession.KEY));
            body.setToken("demo-token");
            var request = GamePacketCodecTest.packet(1003, 42, -3, fory.serialize(body));
            assertEquals(WriteStatus.ACCEPTED, connection.write(request));
            Received login = loginProbe.received.poll(5, TimeUnit.SECONDS);
            assertNotNull(login); assertInstanceOf(LoginReq.class, login.message());
            var decodedLogin = (LoginReq) login.message();
            assertEquals(10001L, decodedLogin.getUserId()); assertEquals(2, decodedLogin.getServerId());
            assertEquals("demo-token", decodedLogin.getToken());
            assertEquals(GameDomain.LOGIN_ID, login.context().routeDomain()); assertEquals(10001L, login.context().routeKey());
            assertEquals(0, login.context().businessIdType()); assertEquals(0, login.context().businessId());
            assertTrue(login.virtual()); assertNotSame(connection, login.context().connection());
            Connection serverConnection = login.context().connection();
            assertEquals(new GameSession(99L, 20002L), serverConnection.get(GameSession.KEY));
            var ping = new PingMessage(Long.MAX_VALUE);
            assertEquals(WriteStatus.ACCEPTED, connection.write(
                    GamePacketCodecTest.packet(1002, 43, 0, fory.serialize(ping))));
            Received role = roleProbe.received.poll(5, TimeUnit.SECONDS);
            assertNotNull(role); assertEquals(ping, role.message()); assertEquals(20002L, role.roleId());
            assertEquals(1, role.context().routeDomain()); assertEquals(99L, role.context().routeKey());
            assertSame(serverConnection, role.context().connection()); assertTrue(role.virtual());
            // A valid Fory value of the wrong protocol type closes without invoking another Handler.
            assertEquals(WriteStatus.ACCEPTED, connection.write(
                    GamePacketCodecTest.packet(1001, 44, 0, fory.serialize(ping))));
            disconnected.get(5, TimeUnit.SECONDS);
            assertEquals(0, responseCount.get()); assertTrue(responses.isEmpty());
            assertTrue(loginProbe.received.isEmpty()); assertTrue(roleProbe.received.isEmpty());
        } finally {
            connections.forEach(Connection::close);
            boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            worker.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static void codecs(ChannelPipeline pipeline) {
        pipeline.addLast(new GamePacketDecoder(), new GamePacketEncoder());
    }

    private abstract static class Handler implements ConnectionHandler {
        @Override public void onConnected(Connection connection) {}
        @Override public void onDisconnected(Connection connection) {}
        @Override public void onException(Connection connection, Throwable cause) { connection.close(); }
    }
}
