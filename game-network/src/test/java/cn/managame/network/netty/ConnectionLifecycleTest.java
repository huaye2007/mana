package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.connection.WriteStatus;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionLifecycleTest {
    private static final class Probe implements ConnectionHandler, ConnectionEstablishment {
        final List<String> events = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        Connection connection;
        boolean admit = true;
        public boolean claimSuccess() { events.add("claim"); return admit; }
        public void release() { events.add("release"); }
        public void success(Connection c) { assertSame(connection, c); events.add("success"); }
        public void networkFailure(Throwable cause) { failures.add(cause); }
        public void onConnected(Connection c) { connection = c; events.add("connected"); }
        public void onMessage(Connection c, Object message) { events.add("message"); }
        public void onDisconnected(Connection c) { events.add("disconnected"); }
        public void onException(Connection c, Throwable cause) { errors.add(cause); }
    }

    /** A new transport declares its own prerequisites without adding lifecycle branches. */
    private static final class Extension implements ChannelTransport {
        ConnectionLifecycle lifecycle;
        ConnectionLifecycle.Handshake first, second;
        public void addProtocolHandlers(io.netty.channel.ChannelPipeline pipeline, ConnectionLifecycle lifecycle) {
            this.lifecycle = lifecycle;
            first = lifecycle.expectHandshake();
            second = lifecycle.expectHandshake();
        }
    }

    @Test void independentHandshakePrerequisitesAreOneShotAndWaitForInitialization() {
        Probe probe = new Probe();
        Extension extension = new Extension();
        EmbeddedChannel channel = new EmbeddedChannel(new NetworkChannelInitializer(probe, List.of(p -> {
            extension.second.succeed();
            extension.second.succeed();
            assertNull(probe.connection, "Duplicate success must not satisfy the other prerequisite");
            extension.first.succeed();
            assertNull(probe.connection, "User pipeline must finish before delivery");
        }), extension, ch -> probe));
        try {
            assertEquals(List.of("claim", "release", "connected", "success"), probe.events);
            extension.first.succeed();
            extension.second.succeed();
            assertEquals(4, probe.events.size());
            assertTrue(probe.failures.isEmpty());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void initializationFailureWinsEvenWhenHandshakesAlreadyCompleted() {
        Probe probe = new Probe();
        Extension extension = new Extension();
        IllegalArgumentException failure = new IllegalArgumentException("configuration");
        EmbeddedChannel channel = new EmbeddedChannel(new NetworkChannelInitializer(probe, List.of(p -> {
            extension.first.succeed();
            extension.second.succeed();
            throw failure;
        }), extension, ch -> probe));
        try {
            channel.runPendingTasks();
            assertNull(probe.connection);
            assertEquals(List.of(failure), probe.failures);
            assertEquals(List.of("release"), probe.events);
            assertFalse(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void lateHandshakeCompletionCannotReviveFailedOrClosedChannel() {
        for (boolean disconnect : new boolean[] {false, true}) {
            Probe probe = new Probe();
            Extension extension = new Extension();
            EmbeddedChannel channel = new EmbeddedChannel(new NetworkChannelInitializer(
                    probe, List.of(), extension, ch -> probe));
            try {
                extension.first.succeed();
                if (disconnect) channel.close();
                else extension.lifecycle.fail(new IllegalStateException("handshake"));
                extension.second.succeed();
                extension.lifecycle.fail(new IllegalStateException("late failure"));
                channel.runPendingTasks();
                assertNull(probe.connection);
                assertEquals(1, probe.failures.size());
                assertEquals(List.of("release"), probe.events);
                assertFalse(channel.isActive());
            } finally { channel.finishAndReleaseAll(); }
        }
    }

    @Test void ownerCancellationRejectsDeliveryWithoutASecondOutcome() {
        Probe probe = new Probe();
        Extension extension = new Extension();
        EmbeddedChannel channel = new EmbeddedChannel(new NetworkChannelInitializer(
                probe, List.of(), extension, ch -> probe));
        try {
            probe.admit = false;
            extension.first.succeed();
            extension.second.succeed();
            channel.runPendingTasks();
            assertEquals(List.of("claim", "release"), probe.events);
            assertNull(probe.connection);
            assertTrue(probe.failures.isEmpty(), "Owner already decided cancellation");
            assertFalse(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void writeFailureBypassesTransportPolicyButTraversesApplicationHandlers() {
        Probe probe = new Probe();
        AtomicInteger transportErrors = new AtomicInteger(), applicationErrors = new AtomicInteger();
        ChannelTransport transport = (pipeline, lifecycle) -> pipeline.addLast(new ChannelInboundHandlerAdapter() {
            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                transportErrors.incrementAndGet();
                ctx.close();
            }
        });
        EmbeddedChannel channel = new EmbeddedChannel(new NetworkChannelInitializer(probe, List.of(p -> {
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
        }), transport, ch -> probe));
        try {
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
            EmbeddedChannel channel = new EmbeddedChannel(new NetworkChannelInitializer(probe, List.of(),
                    WebSocketTransport.server("/game", 1024), ch -> probe));
            try {
                assertNull(probe.connection);
                if (disconnect) channel.close();
                channel.advanceTimeBy(11, TimeUnit.SECONDS);
                channel.runScheduledPendingTasks();
                channel.runPendingTasks();
                assertFalse(channel.isActive());
                assertEquals(1, probe.failures.size());
                if (!disconnect) assertInstanceOf(WebSocketHandshakeException.class, probe.failures.getFirst());
                assertEquals(List.of("release"), probe.events);
            } finally { channel.finishAndReleaseAll(); }
        }
    }
}
