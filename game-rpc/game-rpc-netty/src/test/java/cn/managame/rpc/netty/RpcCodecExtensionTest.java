package cn.managame.rpc.netty;

import cn.managame.rpc.core.RpcHandler;
import cn.managame.rpc.core.RpcNode;
import cn.managame.rpc.core.RpcResult;
import cn.managame.rpc.protocol.RpcProtocolException;
import cn.managame.rpc.protocol.DefaultRpcCodec;
import cn.managame.rpc.protocol.RpcCodec;
import cn.managame.rpc.protocol.RpcHandshake;
import cn.managame.rpc.protocol.RpcMessage;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRouteMessage;

import static cn.managame.rpc.netty.RpcTcpTest.*;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.rpc.core.*;

import io.netty.buffer.*;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class RpcCodecExtensionTest {
    final RpcTcpTest fixture = new RpcTcpTest();

    @AfterEach
    void close() {
        fixture.cleanup();
    }

    RpcNode.Builder builder(int id, RpcCodec codec, RpcHandler handler) {
        fixture.envelopeCodecs.put(id, codec);
        return RpcNode.builder()
                .nodeId(id)
                .listen("127.0.0.1", 0)
                .codec(codec)
                .handler(fixture.recording(id, handler))
                .defaultTimeout(Duration.ofSeconds(3));
    }

    @Test
    void customFormatControlsHandshakeDispatchValidationAndResponseMatching() throws Exception {
        var outgoing = new TaggedCodec(false);
        var incoming = new TaggedCodec(false);

        var a = builder(10, outgoing, (c, m) -> {}).build();
        var b =
                builder(20, incoming, (c, m) -> fixture.reply((RpcMessage) m, body("custom")))
                        .build();
        fixture.nodes.add(a);
        fixture.nodes.add(b);
        fixture.connect(a, b, 2);
        assertEquals(
                "custom",
                fixture.call(a, 20)
                        .get(3, TimeUnit.SECONDS)
                        .value()
                        .body()
                        .toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(2, outgoing.handshakes.get());
        assertEquals(2, incoming.handshakes.get());
        int decoded = incoming.decodes.get();
        var unknown = new TestSignal<RpcResult>();
        a.call(
                20,
                999,
                body("unknown command"),
                RpcOptions.DEFAULT,
                resultValue -> unknown.complete(snapshot(resultValue)));
        assertEquals(body("custom"), unknown.get(3, TimeUnit.SECONDS).value().body());
        assertEquals(decoded + 1, incoming.decodes.get());
        assertEquals(0, a.peer(20).pendingCount());
    }

    @Test
    void customEnvelopeReachesHandlerWithoutInterpretingAddressOrInner() throws Exception {
        var outgoing = new TaggedCodec(false);
        var incoming = new TaggedCodec(false);
        var received = new TestSignal<RpcRouteMessage>();
        var a = builder(10, outgoing, (c, m) -> {}).build();
        var b =
                builder(
                                20,
                                incoming,
                                (c, m) ->
                                        received.complete(
                                                (RpcRouteMessage) snapshot((RpcMessage) m)))
                        .build();
        fixture.nodes.add(a);
        fixture.nodes.add(b);
        fixture.connect(a, b, 1);
        assertTrue(
                a.send(
                        20,
                        new RpcRouteMessage(999, -7, body("not an RPC frame")),
                        RpcOptions.DEFAULT));
        var route = received.get(3, TimeUnit.SECONDS);
        assertEquals(999, route.sourceNodeId());
        assertEquals(-7, route.targetNodeId());
        assertEquals(body("not an RPC frame"), route.inner());
        assertEquals(0, incoming.decodes.get());
        assertEquals(1, outgoing.routes.get());
        assertNull(b.peer(999));
    }

    /** Test format: every frame has a tag; routes use another type code and little-endian IDs. */
    static final class TaggedCodec implements RpcCodec {
        static final int TAG = 0x736a21, PREFIX = 3, ROUTE_HEADER = 12;
        static final byte ROUTE_TYPE = 0x70;
        final DefaultRpcCodec delegate = new DefaultRpcCodec();
        final AtomicInteger handshakes = new AtomicInteger(), routes = new AtomicInteger();
        final AtomicInteger decodes = new AtomicInteger();
        final boolean router;

        TaggedCodec(boolean router) {
            this.router = router;
        }

        ByteBuf content(ByteBuf input) {
            if (input.readableBytes() <= PREFIX
                    || input.getUnsignedMedium(input.readerIndex()) != TAG)
                throw new RpcProtocolException("Missing custom frame tag");
            return input.slice(input.readerIndex() + PREFIX, input.readableBytes() - PREFIX)
                    .asReadOnly();
        }

        ByteBuf tagged(ByteBuf owned) {
            try {
                return Unpooled.buffer(PREFIX + owned.readableBytes())
                        .writeMedium(TAG)
                        .writeBytes(owned, owned.readerIndex(), owned.readableBytes());
            } finally {
                owned.release();
            }
        }

        public ByteBuf encode(RpcMessage message) {
            if (message instanceof RpcHandshake) handshakes.incrementAndGet();
            else assertFalse(router, "Router re-encoded a forwarded message");
            if (message instanceof RpcRouteMessage route) {
                routes.incrementAndGet();
                ByteBuf inner = route.inner();
                return Unpooled.buffer(ROUTE_HEADER + inner.readableBytes())
                        .writeMedium(TAG)
                        .writeByte(ROUTE_TYPE)
                        .writeIntLE(route.sourceNodeId())
                        .writeIntLE(route.targetNodeId())
                        .writeBytes(inner, inner.readerIndex(), inner.readableBytes());
            }
            return tagged(delegate.encode(message));
        }

        public RpcMessage decode(ByteBuf input) {
            ByteBuf bytes = content(input);
            int start = bytes.readerIndex();
            if (bytes.getByte(start) == ROUTE_TYPE) {
                if (bytes.readableBytes() <= 9)
                    throw new RpcProtocolException("Custom route truncated");
                return new RpcRouteMessage(
                        bytes.getIntLE(start + 1),
                        bytes.getIntLE(start + 5),
                        bytes.slice(start + 9, bytes.readableBytes() - 9).asReadOnly());
            }
            var message = delegate.decode(bytes);
            if (!(message instanceof RpcHandshake)) {
                assertFalse(router, "Router decoded an inner message");
                decodes.incrementAndGet();
            }
            return message;
        }
    }
}
