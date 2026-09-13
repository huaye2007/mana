package cn.managame.rpc.core;

import static cn.managame.rpc.core.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class RpcReviewFixTest {
    final RpcNodeTest fixture = new RpcNodeTest();

    @AfterEach
    void close() {
        fixture.close();
    }

    RpcNode add(RpcNode.Builder builder) {
        var node = builder.build();
        fixture.nodes.add(node);
        return node;
    }

    @Test
    void outgoingHandshakeRetriesAfterSharedQuotaBecomesAvailable() throws Exception {
        var a =
                add(
                        fixture.builder(10, (c, m) -> {})
                                .maxPendingHandshakes(1)
                                .handshakeTimeout(Duration.ofSeconds(10)));
        var b = fixture.node(20, (c, m) -> {});
        a.start();
        b.start();
        var blocker = candidate(a);
        var connected = connectResult(a, 20, "127.0.0.1", b.localAddress().getPort());
        await(() -> a.eventCounts().getOrDefault("handshake-overloaded", 0L) > 0);
        assertFalse(connected.isDone());
        blocker.close();
        assertTrue(connected.get(3, TimeUnit.SECONDS).isActive());
        var accepted = candidate(a);
        assertTrue(accepted.isActive());
        assertFalse(candidate(a).isActive());
    }

    Network.Conn candidate(RpcNode node) throws Exception {
        var transport = fixture.network.transports.get(node.nodeId());
        var c = fixture.network.new Conn(transport, true);
        transport.connections.add(c);
        c.handler.onConnected(c);
        return c;
    }

    enum Release {
        DISCONNECT,
        MALFORMED,
        READY,
        TIMEOUT
    }

    @ParameterizedTest
    @EnumSource(Release.class)
    void handshakeQuotaReleasesExactlyOnceOnEveryTerminalPath(Release mode) throws Exception {
        var timeout = mode == Release.TIMEOUT ? Duration.ofMillis(200) : Duration.ofSeconds(20);
        var a =
                add(
                        fixture.builder(10, (c, m) -> {})
                                .maxPendingHandshakes(1)
                                .handshakeTimeout(timeout));
        a.start();
        var first = candidate(a);
        assertTrue(first.isActive());
        for (int i = 0; i < 20; i++) assertFalse(candidate(a).isActive());
        switch (mode) {
            case DISCONNECT -> first.close();
            case MALFORMED -> first.handler.onMessage(first, raw(new byte[] {99}));
            case TIMEOUT -> await(() -> !first.isActive());
            case READY ->
                    first.handler.onMessage(
                            first,
                            new TestCodec(ALLOCATOR, LIMITS).handshake((byte) 4, 20, 10, 0, 1));
        }
        if (mode != Release.READY)
            first.handler.onDisconnected(first); // duplicate event cannot free twice
        var next = candidate(a);
        assertTrue(next.isActive());
        assertFalse(candidate(a).isActive());
        a.close();
        assertFalse(next.isActive());
        assertFalse(first.isActive());
    }
}
