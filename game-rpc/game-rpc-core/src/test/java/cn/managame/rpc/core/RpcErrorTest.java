package cn.managame.rpc.core;

import cn.managame.rpc.protocol.RpcProtocolException;
import cn.managame.rpc.protocol.DefaultRpcCodec;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcMetadata;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRequest;
import cn.managame.rpc.protocol.RpcResponse;

import static cn.managame.rpc.core.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;

import org.junit.jupiter.api.Test;

import java.util.HashMap;

class RpcErrorTest {
    static final RpcError NOT_ENOUGH_GOLD = new RpcError(1001, "金币不足");

    @Test
    void businessDefinitionsReserveTheWholeInternalRange() {
        for (int code = -1; code <= 1000; code++) {
            int reserved = code;
            assertThrows(IllegalArgumentException.class, () -> new RpcError(reserved, "reserved"));
        }
        assertEquals(1001, NOT_ENOUGH_GOLD.code());
        assertEquals("金币不足", NOT_ENOUGH_GOLD.message());
        assertEquals(Integer.MAX_VALUE, new RpcError(Integer.MAX_VALUE, "last").code());
        assertThrows(NullPointerException.class, () -> new RpcError(1001, null));
    }

    @Test
    void wireCodesPreserveUnknownReservedAndBusinessErrors() {
        assertSame(RpcError.TIMEOUT, RpcError.fromCode(6));
        assertSame(RpcError.PROTOCOL_ERROR, RpcError.fromCode(7));
        for (int code : new int[] {8, 1000, 1001, Integer.MAX_VALUE}) {
            var error = RpcError.fromCode(code);
            assertEquals(code, error.code());
            assertEquals("", error.message());
        }
        assertThrows(RpcProtocolException.class, () -> RpcError.fromCode(0));
        assertThrows(RpcProtocolException.class, () -> RpcError.fromCode(-1));
    }

    @Test
    void equalityUsesCodeRatherThanDescriptionsOrInstanceIdentity() {
        var received = RpcError.fromCode(1001);
        var translated = new RpcError(1001, "Not enough gold");
        assertEquals(NOT_ENOUGH_GOLD, received);
        assertEquals(received, NOT_ENOUGH_GOLD);
        assertEquals(translated, received);
        assertEquals(NOT_ENOUGH_GOLD.hashCode(), received.hashCode());
        assertNotEquals(NOT_ENOUGH_GOLD, new RpcError(1002, "金币不足"));
        var map = new HashMap<RpcError, String>();
        map.put(NOT_ENOUGH_GOLD, "template");
        assertEquals("template", map.get(received));
    }

    @Test
    void typedResponseFactoryPreservesWireMetadataAndArguments() {
        var metadata = RpcMetadata.builder().putInt((short) 1024, 42).build();
        var response = RpcResponse.error(17, NOT_ENOUGH_GOLD, metadata, "1000", "300");
        var codec = new DefaultRpcCodec();
        var frame = codec.encode(response);
        try {
            assertEquals(1001, frame.getInt(5));
            var decoded = (RpcResponse) codec.decode(frame);
            assertEquals(17, decoded.requestId());
            assertEquals(42, decoded.metadata().getInt((short) 1024));
            assertArrayEquals(new String[] {"1000", "300"}, decoded.errorArgs());
            assertEquals(NOT_ENOUGH_GOLD, RpcResult.received(decoded).error());
        } finally {
            frame.release();
        }
        assertThrows(NullPointerException.class, () -> RpcResponse.error(1, (RpcError) null));
    }

    @Test
    void onlySuccessHasNoErrorAndResultRejectsMismatchedCodes() {
        var response = RpcResponse.error(1, NOT_ENOUGH_GOLD, "arg");
        var result = RpcResult.received(response);
        assertFalse(result.isSuccess());
        assertNotNull(result.error());
        assertSame(response, result.value());
        assertEquals(NOT_ENOUGH_GOLD, result.error());
        assertEquals(1001, result.errorCode());
        assertEquals(NOT_ENOUGH_GOLD, RpcResult.failure(NOT_ENOUGH_GOLD).error());
        assertNull(RpcResult.failure(NOT_ENOUGH_GOLD).value());
        assertDoesNotThrow(() -> new RpcResult(response, NOT_ENOUGH_GOLD));
        assertThrows(IllegalArgumentException.class, () -> new RpcResult(response, null));
        assertThrows(
                IllegalArgumentException.class, () -> new RpcResult(response, RpcError.TIMEOUT));
        var ok = new RpcResponse(1, 0, RpcMetadata.EMPTY, Unpooled.EMPTY_BUFFER);
        assertTrue(RpcResult.received(ok).isSuccess());
        assertNull(RpcResult.received(ok).error());
        assertThrows(IllegalArgumentException.class, () -> new RpcResult(ok, NOT_ENOUGH_GOLD));
        assertEquals(1000, RpcResult.received(RpcResponse.error(1, 1000)).error().code());
    }

    @Test
    void exceptionSignalsCacheOnlyBuiltinsAndKeepBusinessErrorIdentity() {
        assertSame(RpcException.UNAVAILABLE, RpcException.signal(RpcError.UNAVAILABLE));
        var first = RpcException.signal(NOT_ENOUGH_GOLD);
        var second = RpcException.signal(NOT_ENOUGH_GOLD);
        assertNotSame(first, second);
        assertSame(NOT_ENOUGH_GOLD, first.error());
        assertEquals(0, first.getStackTrace().length);
        assertEquals("金币不足", new RpcException(NOT_ENOUGH_GOLD).getMessage());
        assertEquals(1000, RpcException.signal(RpcError.fromCode(1000)).error().code());
        assertNotSame(RpcError.fromCode(1001), RpcError.fromCode(1001));
    }

    @Test
    void businessErrorsReachCallbackAndDiagnosticsWithoutLosingArguments() throws Exception {
        var fixture = new RpcNodeTest();
        try {
            var a = fixture.node(10, (c, m) -> {});
            var b =
                    fixture.node(
                            20,
                            (c, m) -> {
                                var request = (RpcRequest) m;
                                fixture.receiver(request)
                                        .reply(
                                                c,
                                                RpcResponse.error(
                                                        request.requestId(),
                                                        NOT_ENOUGH_GOLD,
                                                        "1000",
                                                        "300"));
                            });
            fixture.connect(a, b, 1);
            var result = fixture.call(a, 20, RpcOptions.DEFAULT).get();
            assertEquals(NOT_ENOUGH_GOLD, result.error());
            assertArrayEquals(new String[] {"1000", "300"}, result.value().errorArgs());
            assertEquals(0, a.peer(20).pendingCount());
            var diagnostic =
                    fixture.diagnostics.stream()
                            .filter(e -> e.event().equals("call-failure"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(NOT_ENOUGH_GOLD, ((RpcException) diagnostic.cause()).error());
        } finally {
            fixture.close();
        }
    }
}
