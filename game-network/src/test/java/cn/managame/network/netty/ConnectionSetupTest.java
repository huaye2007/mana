package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.connection.WriteStatus;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionSetupTest {
    private static final class Probe implements ConnectionHandler {
        final List<String> events = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>(), errors = new ArrayList<>();
        Connection connection;
        public void onConnected(Connection c) { connection = c; events.add("connected"); }
        public void onMessage(Connection c, Object message) { events.add("message"); }
        public void onDisconnected(Connection c) { events.add("disconnected"); }
        public void onException(Connection c, Throwable cause) { errors.add(cause); }
    }

    private static EmbeddedChannel channel(Probe probe, WebSocketTransport transport,
                                            List<java.util.function.Consumer<ChannelPipeline>> configurers) {
        return new EmbeddedChannel(new ChannelInitializer<Channel>() {
            protected void initChannel(Channel channel) {
                io.netty.util.concurrent.Promise<Connection> result =
                        io.netty.util.concurrent.ImmediateEventExecutor.INSTANCE.newPromise();
                result.addListener(f -> {
                    if (!f.isSuccess()) probe.failures.add(f.cause().getCause());
                });
                ConnectionHandlerAdapter adapter = new ConnectionHandlerAdapter(channel, probe, () -> true, result);
                NetworkChannelInitializer.configure(channel, configurers, transport, adapter);
            }
        });
    }

    @Test void nestedHandshakeNotificationDeliversFirstMessageBeforeReturning() {
        Probe probe = new Probe();
        EmbeddedChannel channel = channel(probe, WebSocketTransport.server("/game", 1024), List.of());
        ByteBuf message = io.netty.buffer.Unpooled.buffer().writeByte(1);
        var immediateOrder = new java.util.concurrent.atomic.AtomicReference<List<String>>();
        try {
            nestedNotification(channel, 7, () -> {
                channel.pipeline().fireUserEventTriggered(
                        new io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete(
                                "/game", new io.netty.handler.codec.http.DefaultHttpHeaders(), null));
                channel.pipeline().context(WebSocketTransport.PROTOCOL).fireChannelRead(
                        new io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame(message));
                immediateOrder.set(List.copyOf(probe.events));
            });
            assertEquals(List.of("connected", "message"), immediateOrder.get(),
                    "Protocol completion and first-message delivery must not depend on deferred Promise listeners");
            channel.runPendingTasks();
            assertEquals(List.of("connected", "message"), probe.events);
            assertTrue(probe.failures.isEmpty());
            assertTrue(probe.errors.isEmpty());
            assertEquals(0, message.refCnt());
            assertTrue(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    private static void nestedNotification(EmbeddedChannel channel, int remaining, Runnable action) {
        io.netty.util.concurrent.Promise<Void> notification = channel.eventLoop().newPromise();
        notification.addListener(f -> {
            if (remaining == 0) action.run();
            else nestedNotification(channel, remaining - 1, action);
        });
        notification.setSuccess(null);
    }

    @Test void writeFailureBypassesTransportPolicyButTraversesApplicationHandlers() {
        Probe probe = new Probe();
        AtomicInteger transportErrors = new AtomicInteger(), applicationErrors = new AtomicInteger();
        WebSocketTransport transport = WebSocketTransport.server("/game", 1024);
        EmbeddedChannel channel = channel(probe, transport, List.of(p -> {
            p.addBefore(WebSocketTransport.PROTOCOL, "protocol-policy", new ChannelInboundHandlerAdapter() {
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    transportErrors.incrementAndGet();
                    ctx.close();
                }
            });
            p.addLast(new MessageToByteEncoder<String>() {
                protected void encode(ChannelHandlerContext ctx, String message, ByteBuf out) {
                    throw new IllegalArgumentException("encode");
                }
            });
            p.addLast(new ChannelInboundHandlerAdapter() {
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    applicationErrors.incrementAndGet();
                    ctx.fireExceptionCaught(cause);
                }
            });
        }));
        try {
            channel.pipeline().fireUserEventTriggered(new io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete(
                    "/game", new io.netty.handler.codec.http.DefaultHttpHeaders(), null));
            assertEquals(WriteStatus.ACCEPTED, probe.connection.write("message"));
            assertEquals(0, transportErrors.get());
            assertEquals(1, applicationErrors.get());
            assertEquals(1, probe.errors.size());
            assertTrue(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void websocketServerBoundsSilentPeersAndCancelsDeadlineOnDisconnect() {
        for (boolean disconnect : new boolean[] {false, true}) {
            Probe probe = new Probe();
            EmbeddedChannel channel = channel(probe, WebSocketTransport.server("/game", 1024), List.of());
            try {
                assertNull(probe.connection);
                if (disconnect) channel.close();
                long deadline = io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig.newBuilder()
                        .build().handshakeTimeoutMillis();
                if (!disconnect) {
                    channel.advanceTimeBy(deadline / 2, TimeUnit.MILLISECONDS);
                    channel.runScheduledPendingTasks();
                    assertTrue(channel.isActive());
                    assertTrue(probe.failures.isEmpty());
                    channel.advanceTimeBy(deadline - deadline / 2 + 1, TimeUnit.MILLISECONDS);
                } else channel.advanceTimeBy(deadline + 1, TimeUnit.MILLISECONDS);
                channel.runScheduledPendingTasks();
                channel.runPendingTasks();
                assertFalse(channel.isActive());
                assertEquals(1, probe.failures.size());
                if (!disconnect) assertInstanceOf(WebSocketHandshakeException.class, probe.failures.getFirst());
                assertTrue(probe.events.isEmpty());
            } finally { channel.finishAndReleaseAll(); }
        }
    }

    @Test void websocketClientUsesNativeDeadlineForSilentUpgrade() {
        Probe probe = new Probe();
        EmbeddedChannel channel = channel(probe, WebSocketTransport.client(
                java.net.URI.create("ws://127.0.0.1/game"),
                cn.managame.network.connector.WebSocketConnectOptions.defaults(), 1024), List.of());
        try {
            long deadline = io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig.newBuilder()
                    .build().handshakeTimeoutMillis();
            channel.advanceTimeBy(deadline / 2, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertTrue(channel.isActive());
            assertTrue(probe.failures.isEmpty());
            channel.advanceTimeBy(deadline - deadline / 2 + 1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();
            assertFalse(channel.isActive());
            assertEquals(1, probe.failures.size());
            assertInstanceOf(WebSocketHandshakeException.class, probe.failures.getFirst());
            assertNull(probe.connection);
            assertTrue(probe.events.isEmpty());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void handlerAddedFailureCannotBeReportedAsSuccessfulInitialization() {
        Probe probe = new Probe();
        EmbeddedChannel channel = channel(probe, null, List.of(p -> p.addLast(
                new ChannelInboundHandlerAdapter() {
                    @Override public void handlerAdded(ChannelHandlerContext ctx) {
                        throw new IllegalArgumentException("handlerAdded");
                    }
                })));
        try {
            channel.runPendingTasks();
            assertNull(probe.connection);
            assertEquals(1, probe.failures.size());
            assertTrue(probe.events.isEmpty());
            assertFalse(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }
}
