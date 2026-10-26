package cn.managame.network.netty;

import static cn.managame.network.netty.TestSupport.serve;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.network.connection.Connection;
import cn.managame.network.netty.TestSupport.RecordingHandler;
import cn.managame.network.netty.TestSupport.TestClient;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConnectionMapperTest {
    /** An application Connection type carrying session state. */
    static final class GameSession extends NettyConnection {
        volatile long playerId;

        GameSession(Channel channel) { super(channel); }
    }

    /** Keeps sessions in the application's own table instead of a channel attribute. */
    static class SessionTable implements ConnectionMapper {
        final Map<ChannelId, Connection> sessions = new ConcurrentHashMap<>();
        final AtomicInteger removed = new AtomicInteger();

        @Override public Connection create(Channel channel) {
            GameSession session = new GameSession(channel);
            sessions.put(channel.id(), session);
            return session;
        }
        @Override public Connection get(Channel channel) { return sessions.get(channel.id()); }
        @Override public void remove(Channel channel) {
            sessions.remove(channel.id());
            removed.incrementAndGet();
        }
    }

    /** Also records which Connection object each callback received. */
    static final class TrackingHandler extends RecordingHandler {
        final BlockingQueue<Connection> received = new LinkedBlockingQueue<>();

        @Override public void onRead(Connection connection, Object message) {
            received.add(connection);
            super.onRead(connection, message);
        }
        @Override public void onInActive(Connection connection) {
            received.add(connection);
            super.onInActive(connection);
        }
    }

    @Test void defaultMapperKeepsTheConnectionInAChannelAttribute() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        AtomicReference<Connection> seenByNettyHandler = new AtomicReference<>();
        ChannelInboundHandlerAdapter lookup = new ChannelInboundHandlerAdapter() {
            @Override public boolean isSharable() { return true; }
            @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                seenByNettyHandler.set(ctx.channel().attr(NetworkAttributes.CONNECTION).get());
                ctx.fireChannelRead(message);
            }
        };
        try (NetworkServer server = serve(ConnectionInitializer.tcp().addLast("lookup", lookup)
                .handler(handler));
             TestClient client = new TestClient(server.port(), p -> {})) {
            assertEquals("active", handler.next());
            Connection connection = handler.connections.take();
            assertInstanceOf(NettyConnection.class, connection);
            client.send(Unpooled.copiedBuffer("x", UTF_8));
            assertEquals("read:x", handler.next());
            assertSame(connection, seenByNettyHandler.get());

            client.close();
            assertEquals("inactive", handler.next());
            Channel channel = ((NettyConnection) connection).channel();
            for (int i = 0; i < 100 && channel.attr(NetworkAttributes.CONNECTION).get() != null; i++)
                TimeUnit.MILLISECONDS.sleep(20);
            assertNull(channel.attr(NetworkAttributes.CONNECTION).get(), "remove clears the mapping after onInActive");
        }
    }

    @Test void customMapperDecidesCreationAndEveryLookup() throws Exception {
        SessionTable mapper = new SessionTable();
        TrackingHandler handler = new TrackingHandler();
        try (NetworkServer server = serve(ConnectionInitializer.tcp().connectionMapper(mapper)
                .handler(handler));
             TestClient client = new TestClient(server.port(), p -> {})) {
            assertEquals("active", handler.next());
            GameSession created = assertInstanceOf(GameSession.class, handler.connections.take());
            assertSame(created, mapper.sessions.get(created.channel().id()));
            assertNull(created.channel().attr(NetworkAttributes.CONNECTION).get(), "a custom mapper decides storage");

            // Resume: the application maps the channel to another session; the next callback follows it.
            GameSession resumed = new GameSession(created.channel());
            resumed.playerId = 42;
            mapper.sessions.put(created.channel().id(), resumed);
            client.send(Unpooled.copiedBuffer("x", UTF_8));
            assertEquals("read:x", handler.next());
            assertSame(resumed, handler.received.take());
            assertEquals("x", client.next());

            // Unmapped: callbacks are skipped and the message released; the channel stays open.
            mapper.sessions.remove(created.channel().id());
            client.send(Unpooled.copiedBuffer("y", UTF_8));
            assertNull(handler.poll(200));
            assertTrue(client.channel.isActive());

            mapper.sessions.put(created.channel().id(), resumed);
            client.close();
            assertEquals("inactive", handler.next());
            assertSame(resumed, handler.received.take());
            for (int i = 0; i < 100 && mapper.removed.get() == 0; i++) TimeUnit.MILLISECONDS.sleep(20);
            assertEquals(1, mapper.removed.get());
            assertTrue(mapper.sessions.isEmpty());
        }
    }

    @Test void failedOrNullCreationClosesWithoutOnActive() throws Exception {
        for (ConnectionMapper broken : new ConnectionMapper[] {
                new SessionTable() { @Override public Connection create(Channel channel) { throw new IllegalStateException("broken"); } },
                new SessionTable() { @Override public Connection create(Channel channel) { return null; } }}) {
            RecordingHandler handler = new RecordingHandler();
            try (NetworkServer server = serve(ConnectionInitializer.tcp().connectionMapper(broken)
                    .handler(handler));
                 TestClient client = new TestClient(server.port(), p -> {})) {
                assertTrue(client.awaitClosed());
                assertNull(handler.poll(200));
            }
        }
    }
}
