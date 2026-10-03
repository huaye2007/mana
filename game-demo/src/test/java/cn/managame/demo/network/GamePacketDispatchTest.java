package cn.managame.demo.network;

import cn.managame.core.FrameworkErrorCodes;
import cn.managame.demo.bus.user.LoginReq;
import cn.managame.demo.common.protocol.GameProtocols;
import cn.managame.demo.common.runtime.GameDomain;
import cn.managame.demo.common.serialization.ForyConfig;
import cn.managame.demo.network.message.DemoMessage;
import cn.managame.demo.network.message.PingMessage;
import cn.managame.network.connection.Connection;
import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.context.ClientHandlerContext;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.executor.RouteExecuteStatus;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.handler.*;
import cn.managame.runtime.route.RouteDomain;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class GamePacketDispatchTest {
    @Test void loginRoutesWithoutAConnectionSessionAndNeverCreatesOne() {
        var connection = connection();
        var request = new LoginReq(); request.setUserId(42L);
        var context = GameDomain.LOGIN.handlerContext(connection, request);
        assertEquals(GameDomain.LOGIN_ID, context.routeDomain()); assertEquals(42L, context.routeKey());
        assertEquals(0, context.businessIdType()); assertEquals(0, context.businessId());
        assertSame(request, context.message()); assertSame(connection, context.connection());
        assertNull(connection.get(GameSession.KEY));
        connection.set(GameSession.KEY, new GameSession(99L, 0L));
        var roleContext = GameDomain.ROLE.handlerContext(connection, new PingMessage(1L));
        assertEquals(99L, roleContext.routeKey()); assertEquals(0L, roleContext.businessId());
        assertEquals(GameDomain.ROLE_BUSINESS_ID_TYPE, roleContext.businessIdType());
    }

    record Received(long roleId, PingMessage message, ClientHandlerContext context) {}
    @Handler(domain = 1) @Profile("manual-packet-dispatch-only")
    static class BusinessHandler {
        final List<Received> received = new ArrayList<>();
        @HandlerMethod public void ping(PingMessage message, ClientHandlerContext context) {
            received.add(new Received(context.businessId(), message, context));
        }
    }

    static Connection connection() {
        return connection(new ArrayList<>());
    }
    static Connection connection(List<GamePacket> writes) {
        var attributes = new ConcurrentHashMap<AttributeKey<?>, Object>();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> attributes.get(args[0]);
                    case "set" -> { attributes.put((AttributeKey<?>) args[0], args[1]); yield null; }
                    case "remove" -> attributes.remove(args[0]);
                    case "write" -> { writes.add((GamePacket) args[0]); yield cn.managame.network.connection.WriteStatus.ACCEPTED; }
                    default -> throw new AssertionError("Unexpected Connection operation: " + method.getName());
                });
    }

    @Test void decodedMessageAndIdentityAreCapturedBeforeQueueingWithoutEchoingPacket() {
        var tasks = new ArrayDeque<Runnable>();
        var handler = new BusinessHandler();
        try (var runtime = GameRuntimeBuilder.builder().routeDomains(List.of(RouteDomain.of(1, "demo")))
                .routeExecutors(List.of(RouteExecutorBinding.of((d, k, t) -> {
                    tasks.add(t); return RouteExecuteStatus.ACCEPTED;
                }, 1))).protocols(List.of(new GameProtocols())).handlers(List.of(handler))
                .handlerContextFactory((domain, connection, metadata, message) -> GameDomain.fromId(domain)
                        .handlerContext(connection, metadata, message))
                .build()) {
            var fory = new ForyConfig().fory(new GameProtocols());
            var packets = new GamePacketHandler(fory, runtime);
            var writes = new ArrayList<GamePacket>();
            var connection = connection(writes); var other = connection();
            packets.onConnected(connection); packets.onConnected(other);
            assertNull(connection.get(GameSession.KEY));
            assertNull(other.get(GameSession.KEY));
            var request = new PingMessage(1234L);
            var packet = GamePacketCodecTest.packet(1002, 8, 0, fory.serialize(request));
            assertThrows(IllegalArgumentException.class, () -> packets.onMessage(connection, packet));
            assertTrue(tasks.isEmpty());
            connection.set(GameSession.KEY, new GameSession(99L, 10001L));
            packets.onConnected(connection);
            assertEquals(new GameSession(99L, 10001L), connection.get(GameSession.KEY));
            packets.onMessage(connection, packet);
            assertEquals(1, tasks.size()); assertTrue(handler.received.isEmpty());
            connection.set(GameSession.KEY, new GameSession(88L, 20002L));
            packet.setBody(new byte[0]); // Queued work owns the decoded object, not the original packet body.
            tasks.remove().run();
            var received = handler.received.getFirst();
            assertEquals(request, received.message()); assertEquals(10001L, received.roleId());
            assertEquals(99L, received.context().routeKey()); assertSame(connection, received.context().connection());
            assertEquals(10001L, received.context().businessId());
            assertEquals(1002, received.context().metadata().get(GamePacketMetadata.COMMAND));
            assertEquals(8, received.context().metadata().get(GamePacketMetadata.SEQ));
            assertEquals(0, received.context().metadata().get(GamePacketMetadata.CODE));
            var missingHandler = GamePacketCodecTest.packet(1001, 9, 0, fory.serialize(new DemoMessage(1L, "no handler")));
            packets.onMessage(connection, missingHandler);
            assertEquals(1, writes.size());
            assertEquals(FrameworkErrorCodes.HANDLER_NOT_FOUND, writes.getFirst().getCode());
            assertEquals(1001, writes.getFirst().getCommand()); assertEquals(9, writes.getFirst().getSeq());
            assertEquals(0, writes.getFirst().getBody().length);
            assertTrue(tasks.isEmpty());
            other.remove(GameSession.KEY);
            assertThrows(IllegalArgumentException.class, () -> packets.onMessage(other,
                    GamePacketCodecTest.packet(1002, 10, 0, fory.serialize(request))));
            assertTrue(tasks.isEmpty());
        }
    }
}
