package cn.managame.rpc.netty;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.rpc.*;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class RpcHeartbeatTcpTest {
    static class Codec extends DefaultRpcCodec {
        final AtomicInteger pings = new AtomicInteger(), pongs = new AtomicInteger();

        @Override
        public ByteBuf encode(RpcMessage message) {
            if (message instanceof RpcHeartbeat h)
                (h.kind() == RpcHeartbeat.Kind.PING ? pings : pongs).incrementAndGet();
            return super.encode(message);
        }
    }

    RpcNode.Builder builder(int id) {
        return RpcNode.builder()
                .nodeId(id)
                .listen("127.0.0.1", 0)
                .defaultTimeout(Duration.ofSeconds(2))
                .heartbeatInterval(Duration.ofMillis(100))
                .heartbeatTimeout(Duration.ofMillis(500))
                .handler((c, m) -> fail("Control message reached business handler"));
    }

    @Test
    void inboundNetworkReadIdleExpiresOldSlotAndAdmitsReplacement() throws Exception {
        try (var b = builder(20).readIdleTimeout(Duration.ofMillis(300)).build()) {
            b.start();
            try (var old = new Socket("127.0.0.1", b.localAddress().getPort())) {
                handshake(old);
                var peer = b.peer(10);
                var connection = awaitConnection(peer);
                assertEquals(
                        -1, old.getInputStream().read(), "READ_IDLE must close the silent peer");
                assertFalse(connection.isActive());
                try (var replacement = new Socket("127.0.0.1", b.localAddress().getPort())) {
                    handshake(replacement);
                    assertSame(peer, b.peer(10));
                    assertNotSame(connection, awaitConnection(peer));
                }
            }
        }
    }

    private static cn.managame.network.Connection awaitConnection(RpcPeer peer) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (peer.connection(0) == null && System.nanoTime() < deadline) Thread.sleep(1);
        var connection = peer.connection(0);
        assertNotNull(connection);
        return connection;
    }

    private static void handshake(Socket socket) throws Exception {
        socket.setSoTimeout(5000);
        var out = new DataOutputStream(socket.getOutputStream());
        out.writeInt(17);
        out.writeByte(4);
        out.writeInt(10);
        out.writeInt(20);
        out.writeInt(0);
        out.writeInt(1);
        out.flush();
        var in = new DataInputStream(socket.getInputStream());
        assertEquals(17, in.readInt());
        assertEquals(5, in.readByte());
        assertEquals(20, in.readInt());
        assertEquals(10, in.readInt());
        assertEquals(0, in.readInt());
        assertEquals(1, in.readInt());
    }

    @Test
    void idleTcpConnectionIsProbedThroughConfiguredCodecInOneDirection() throws Exception {
        var clientCodec = new Codec();
        var serverCodec = new Codec();
        try (var a = builder(10).codec(clientCodec).build();
                var b =
                        builder(20)
                                .codec(serverCodec)
                                .readIdleTimeout(Duration.ofSeconds(1))
                                .build()) {
            b.start();
            a.start();
            a.connect(20, "127.0.0.1", b.localAddress().getPort());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (serverCodec.pongs.get() < 3 && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(serverCodec.pongs.get() >= 3);
            assertTrue(clientCodec.pings.get() >= 3);
            assertEquals(0, serverCodec.pings.get());
            assertEquals(0, clientCodec.pongs.get());
            assertTrue(a.peer(20).isReady());
            assertEquals(0L, a.eventCounts().getOrDefault("heartbeat-timeout", 0L));
        }
    }

    @Test
    void silentTcpPeerIsClosedAfterProbeDeadline() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var a = builder(10).build()) {
            server.setSoTimeout(5000);
            a.start();
            a.connect(20, "127.0.0.1", server.getLocalPort());
            try (var socket = server.accept()) {
                socket.setSoTimeout(5000);
                var input = new DataInputStream(socket.getInputStream());
                var output = new DataOutputStream(socket.getOutputStream());
                assertEquals(17, input.readInt());
                assertEquals(4, input.readByte());
                assertEquals(10, input.readInt());
                assertEquals(20, input.readInt());
                int slot = input.readInt(), count = input.readInt();
                output.writeInt(17);
                output.writeByte(5);
                output.writeInt(20);
                output.writeInt(10);
                output.writeInt(slot);
                output.writeInt(count);
                output.flush();
                assertEquals(5, input.readInt());
                assertEquals(7, input.readByte());
                assertEquals(1, input.readInt());
                assertEquals(-1, input.read(), "Missing PONG must close an otherwise open socket");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (a.eventCounts().getOrDefault("heartbeat-timeout", 0L) == 0
                        && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(1L, a.eventCounts().getOrDefault("heartbeat-timeout", 0L));
            }
        }
    }
}
