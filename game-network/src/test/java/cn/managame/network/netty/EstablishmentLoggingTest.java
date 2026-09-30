package cn.managame.network.netty;

import cn.managame.network.connection.*;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import org.junit.jupiter.api.Test;
import java.nio.channels.ClosedChannelException;
import javax.net.ssl.SSLHandshakeException;
import java.util.*;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class EstablishmentLoggingTest {
    private static final class Capture implements AutoCloseable {
        final Logger logger = Logger.getLogger("cn.managame.network");
        final Level level = logger.getLevel();
        final boolean parents = logger.getUseParentHandlers();
        final List<LogRecord> records = new ArrayList<>();
        final Handler sink = new Handler() {
            public void publish(LogRecord record) { records.add(record); }
            public void flush() {}
            public void close() {}
        };
        Capture(Level requested) {
            sink.setLevel(Level.ALL);
            logger.addHandler(sink);
            logger.setUseParentHandlers(false);
            logger.setLevel(requested);
        }
        public void close() {
            logger.removeHandler(sink);
            logger.setLevel(level);
            logger.setUseParentHandlers(parents);
        }
    }

    private static void fail(Throwable failure) { fail(failure, false); }

    private static void fail(Throwable failure, boolean late) {
        ConnectionHandler handler = new ConnectionHandler() {
            public void onConnected(Connection c) { fail("No business connection expected"); }
            public void onMessage(Connection c, Object m) { fail("No message expected"); }
            public void onDisconnected(Connection c) { fail("No business disconnect expected"); }
            public void onException(Connection c, Throwable cause) { fail("No business error expected"); }
            private void fail(String message) { throw new AssertionError(message); }
        };
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            protected void initChannel(Channel ch) {
                ConnectionHandlerAdapter adapter = new ConnectionHandlerAdapter(ch, handler, () -> true, null);
                adapter.failEstablishment(failure);
                adapter.failEstablishment(failure); // Repeated closure must not duplicate diagnostics.
                if (late) adapter.exceptionCaught(null, failure);
            }
        });
        try {
            channel.runPendingTasks();
            assertFalse(channel.isOpen());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void disconnectAndProtocolRejectionAreStacklessDebugWithoutPeerText() {
        try (Capture capture = new Capture(Level.ALL)) {
            fail(new ClosedChannelException());
            fail(new WebSocketHandshakeException("secret-header\r\ninjected-log-line"));
            fail(new DecoderException(new SSLHandshakeException("secret-certificate-detail")));
            assertEquals(3, capture.records.size());
            for (LogRecord record : capture.records) {
                assertEquals(Level.FINE, record.getLevel());
                assertNull(record.getThrown());
                assertFalse(record.getMessage().contains("secret"));
                assertFalse(record.getMessage().contains("\n"));
            }
            assertTrue(capture.records.get(0).getMessage().contains("reason=Peer disconnected"));
            assertTrue(capture.records.get(1).getMessage().contains("reason=Protocol establishment rejected"));
        }
    }

    @Test void expectedFailuresAreQuietAtInfoWhileProgrammingErrorsKeepOriginalStack() {
        try (Capture capture = new Capture(Level.INFO)) {
            fail(new ClosedChannelException());
            fail(new WebSocketHandshakeException("rejected"));
            assertTrue(capture.records.isEmpty());
            Throwable bug = new DecoderException(new IllegalArgumentException("bad codec"));
            fail(bug);
            assertEquals(1, capture.records.size());
            assertEquals(Level.SEVERE, capture.records.getFirst().getLevel());
            assertSame(bug, capture.records.getFirst().getThrown());
        }
    }

    @Test void lateProtocolErrorsDoNotReturnAsErrorStackTraces() {
        try (Capture capture = new Capture(Level.ALL)) {
            fail(new DecoderException(new SSLHandshakeException("rejected")), true);
            assertEquals(2, capture.records.size());
            assertTrue(capture.records.stream().allMatch(r -> r.getLevel() == Level.FINE && r.getThrown() == null));
            assertTrue(capture.records.getLast().getMessage().contains("outside connection lifecycle"));
        }
    }
}
