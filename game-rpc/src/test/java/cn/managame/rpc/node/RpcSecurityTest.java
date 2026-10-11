package cn.managame.rpc.node;

import cn.managame.rpc.message.*;
import cn.managame.rpc.netty.RpcWire;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Handshake authentication and transport extension points. */
class RpcSecurityTest extends RpcTestSupport {
    static final byte[] SECRET = "cluster-secret-0123456789".getBytes(StandardCharsets.UTF_8);
    static final byte[] OTHER = "another-secret-0123456789".getBytes(StandardCharsets.UTF_8);

    static RpcNode secured(int id, Probe probe, byte[] secret) {
        var builder = RpcNode.builder().nodeId(id).bindAddress(LOCAL).handler(probe).reconnectDelay(Duration.ofMillis(50));
        if (secret != null) builder.handshakeSecret(secret);
        RpcNode node = builder.build(); probe.node = node; node.start(); return node;
    }
    static byte[] nonce() { byte[] n = new byte[16]; new SecureRandom().nextBytes(n); return n; }
    static ByteBuf hello(int id, byte[] secret, long time, byte[] nonce) {
        return RpcWire.encodeHandshake(new RpcHandshake(id, 0, 1), new RpcWire.HandshakeProof(time, nonce, new byte[16]), secret);
    }

    @Test void sameSecretConnectsAndMismatchedConfigurationNeverDoes() throws Exception {
        Probe pa = new Probe(), pb = new Probe();
        try (RpcNode a = secured(1, pa, SECRET); RpcNode b = secured(2, pb, SECRET)) {
            a.addPeer(2, b.localAddress(), 1);
            await(() -> a.isPeerConnected(2) && b.isPeerConnected(1));
            assertEquals(cn.managame.rpc.transport.RpcSendStatus.ACCEPTED, a.notify(2, new RpcRequest(7, null)));
            assertEquals(7, take(pb.requests).command());
        }
        for (byte[][] pair : new byte[][][] {{SECRET, OTHER}, {SECRET, null}, {null, SECRET}}) {
            try (RpcNode a = secured(1, new Probe(), pair[0]); RpcNode b = secured(2, new Probe(), pair[1])) {
                a.addPeer(2, b.localAddress(), 1);
                Thread.sleep(300);   // several reconnect attempts
                assertFalse(a.isPeerConnected(2)); assertFalse(b.peers.containsKey(1));
            }
        }
    }

    @Test void replayedStaleOrForgedHandshakesAreRejected() {
        try (RpcNode n = secured(1, new Probe(), SECRET)) {
            byte[] nonce = nonce(); long now = System.currentTimeMillis();
            Fake first = new Fake(); n.connected(first);
            receive(n, first, hello(2, SECRET, now, nonce));
            assertTrue(first.active); assertEquals(1, first.frames.size());     // reply sent
            Fake replay = new Fake(); n.connected(replay);
            receive(n, replay, hello(3, SECRET, now, nonce));                  // same nonce, other identity
            assertFalse(replay.active);
            Fake stale = new Fake(); n.connected(stale);
            receive(n, stale, hello(4, SECRET, now - 120_000, nonce()));
            assertFalse(stale.active);
            Fake forged = new Fake(); n.connected(forged);
            receive(n, forged, hello(5, OTHER, now, nonce()));
            assertFalse(forged.active);
            Fake unsigned = new Fake(); n.connected(unsigned);
            receive(n, unsigned, RpcWire.encodeHandshake(new RpcHandshake(6, 0, 1)));
            assertFalse(unsigned.active);
            assertEquals(List.of(2), n.peers.keySet().stream().toList());
        }
    }

    @Test void transportHandlersPrecedeRpcFramingAndOptionsApply() throws Exception {
        var names = new LinkedBlockingQueue<List<String>>();
        var marks = new LinkedBlockingQueue<WriteBufferWaterMark>();
        Probe pa = new Probe(), pb = new Probe();
        var builder = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(pa)
                .writeBufferWaterMark(256 * 1024, 1024 * 1024)
                .transport(p -> p.addLast("probe", new ChannelInboundHandlerAdapter() {
                    @Override public void channelActive(ChannelHandlerContext ctx) {
                        names.add(ctx.pipeline().names());
                        marks.add(ctx.channel().config().getWriteBufferWaterMark());
                        ctx.fireChannelActive();
                    }
                }));
        RpcNode a = builder.build(); pa.node = a; a.start();
        try (a; RpcNode b = node(2, pb)) {
            a.addPeer(2, b.localAddress(), 1);
            List<String> pipeline = take(names);
            assertTrue(pipeline.indexOf("probe") < pipeline.indexOf("rpc-frame"), pipeline.toString());
            assertEquals(1024 * 1024, take(marks).high());
            await(() -> a.isPeerConnected(2));
        }
        assertThrows(IllegalArgumentException.class, () -> RpcNode.builder().handshakeSecret(new byte[8]));
    }
}
