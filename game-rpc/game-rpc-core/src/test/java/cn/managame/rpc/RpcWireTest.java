package cn.managame.rpc;

import static cn.managame.rpc.RpcMetadataUtil.*;
import static cn.managame.rpc.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.*;

import org.junit.jupiter.api.*;

import java.nio.ReadOnlyBufferException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

class RpcWireTest {
    static final short LONG = 1024, STRING = 1025;
    static final RpcTestSupport.TestCodec WIRE =
            new RpcTestSupport.TestCodec(ALLOCATOR, RpcLimits.DEFAULT);

    static RpcOptions options(long route, long business, byte type, RpcMetadata metadata) {
        return new RpcOptions(route, type, business, metadata, null);
    }

    static ByteBuf empty() {
        return Unpooled.EMPTY_BUFFER;
    }

    static ByteBuf call() {
        return WIRE.request(1, 1, options(2, 3, (byte) 1, RpcMetadata.EMPTY), empty());
    }

    static ByteBuf expected(String name) {
        return switch (name) {
            case "call_empty" -> call();
            case "metadata_opaque_bytes" ->
                    WIRE.request(
                            1,
                            1,
                            options(
                                    2,
                                    3,
                                    (byte) 1,
                                    RpcMetadata.builder()
                                            .put(STRING, new byte[] {(byte) 0xff})
                                            .build()),
                            empty());
            case "metadata_int_boolean" ->
                    WIRE.request(
                            1,
                            1,
                            options(
                                    2,
                                    3,
                                    (byte) 1,
                                    RpcOptions.builder()
                                            .putInt((short) 1026, Integer.MIN_VALUE)
                                            .putBoolean((short) 1027, true)
                                            .putBoolean((short) 1028, false)
                                            .build()
                                            .metadata()),
                            empty());
            case "send_empty" ->
                    WIRE.request(1, 0, options(2, 3, (byte) 1, RpcMetadata.EMPTY), empty());
            case "response_success_empty" -> WIRE.response(1, 0, RpcMetadata.EMPTY, empty());
            case "response_error_args" -> WIRE.encode(RpcResponse.error(1, 10001, "1000", "300"));
            case "response_error_unicode" -> WIRE.encode(RpcResponse.error(1, 10001, "", "金币😀"));
            case "response_error" -> WIRE.response(1, 1, RpcMetadata.EMPTY, empty());
            case "routed_call" -> WIRE.route(10, 20, call());
            case "routed_response" ->
                    WIRE.route(20, 10, WIRE.response(1, 0, RpcMetadata.EMPTY, empty()));
            case "request_id_max" ->
                    WIRE.request(
                            1,
                            Integer.MAX_VALUE,
                            options(2, 3, (byte) 1, RpcMetadata.EMPTY),
                            empty());
            case "signed_node_ids" -> WIRE.route(Integer.MIN_VALUE, Integer.MAX_VALUE, call());
            case "long_boundaries" ->
                    WIRE.request(
                            1,
                            1,
                            options(Long.MIN_VALUE, Long.MAX_VALUE, (byte) 127, RpcMetadata.EMPTY),
                            empty());
            case "metadata_signed_utf8" ->
                    WIRE.request(
                            1,
                            123456789,
                            options(
                                    -2,
                                    -3,
                                    (byte) 1,
                                    RpcOptions.builder()
                                            .putLong(LONG, -1)
                                            .putString(STRING, "中")
                                            .build()
                                            .metadata()),
                            raw(new byte[] {(byte) 0xaa, (byte) 0xbb}));
            case "metadata_key_max" ->
                    WIRE.request(
                            1,
                            1,
                            options(
                                    2,
                                    3,
                                    (byte) 1,
                                    RpcMetadata.copyOf(
                                            raw(new byte[] {0x7f, (byte) 0xff, 0}), 1024)),
                            empty());
            case "command_max" ->
                    WIRE.request(
                            Integer.MAX_VALUE,
                            1,
                            options(2, 3, (byte) 1, RpcMetadata.EMPTY),
                            empty());
            case "error_code_reserved_max" -> WIRE.encode(RpcResponse.error(1, 1000));
            case "error_code_business_min" -> WIRE.encode(RpcResponse.error(1, 1001));
            case "error_code_max" ->
                    WIRE.response(1, Integer.MAX_VALUE, RpcMetadata.EMPTY, empty());
            case "heartbeat_ping" -> WIRE.encode(new RpcHeartbeat(RpcHeartbeat.Kind.PING, 1));
            case "heartbeat_pong" -> WIRE.encode(new RpcHeartbeat(RpcHeartbeat.Kind.PONG, 1));
            case "heartbeat_sequence_max" ->
                    WIRE.encode(new RpcHeartbeat(RpcHeartbeat.Kind.PING, Integer.MAX_VALUE));
            case "handshake" -> WIRE.handshake((byte) 4, 10, 20, 0, 2);
            case "handshake_ack" -> WIRE.handshake((byte) 5, 20, 10, 0, 2);
            case "handshake_reject_direction" -> WIRE.handshake((byte) 6, 20, 10, 0, 2);
            case "metadata_value_max" ->
                    WIRE.request(
                            1,
                            1,
                            options(
                                    2,
                                    3,
                                    (byte) 1,
                                    RpcOptions.builder()
                                            .putString(STRING, "中".repeat(41) + "abcd")
                                            .build()
                                            .metadata()),
                            empty());
            case "metadata_total_over_127" -> {
                var meta =
                        RpcMetadata.builder()
                                .put((short) 1026, raw("a".repeat(64).getBytes()))
                                .put((short) 1027, raw("b".repeat(64).getBytes()))
                                .build();
                yield WIRE.request(1, 1, options(2, 3, (byte) 1, meta), empty());
            }
            default -> throw new AssertionError("Missing independent expected vector " + name);
        };
    }

    static ByteBuf encode(RpcMessage frame) {
        return WIRE.encode(frame);
    }

    static void validateDestination(ByteBuf frame) {
        var decoded = WIRE.decode(frame);
        if (decoded instanceof RpcRouteMessage route) {
            var inner = WIRE.decode(route.inner());
            if (!(inner instanceof RpcRequest || inner instanceof RpcResponse))
                throw new RpcProtocolException(
                        "Only Request/Response may be delivered from a route");
        }
    }

    @TestFactory
    Stream<DynamicTest> sharedVectors() throws Exception {
        String json = Files.readString(Path.of("../docs/OGBS Game RPC v1 wire vectors.json"));
        var matcher =
                Pattern.compile(
                                "\"name\"\\s*:\\s*\"([^\"]+)\".*?\"hex\"\\s*:\\s*\"([0-9a-f]+)\"",
                                Pattern.DOTALL)
                        .matcher(json);
        var tests = new ArrayList<DynamicTest>();
        int invalid = json.indexOf("\"invalid\"");
        while (matcher.find()) {
            String name = matcher.group(1);
            byte[] data = HexFormat.of().parseHex(matcher.group(2));
            boolean valid = matcher.start() < invalid;
            tests.add(
                    DynamicTest.dynamicTest(
                            name,
                            () -> {
                                if (!valid)
                                    assertThrows(
                                            RpcProtocolException.class,
                                            () -> validateDestination(raw(data)));
                                else {
                                    assertArrayEquals(data, bytes(expected(name)));
                                    assertArrayEquals(data, bytes(encode(WIRE.decode(raw(data)))));
                                }
                            }));
        }
        assertEquals(64, tests.size());
        return tests.stream();
    }

    @Test
    void metadataBoundariesOwnershipAndTypes() {
        var builder = RpcOptions.builder().putLong(LONG, Long.MIN_VALUE);
        var first = builder.build().metadata();
        builder.putString(STRING, "中".repeat(41) + "abcd");
        var second = builder.build().metadata();
        assertFalse(first.contains(STRING));
        assertEquals(Long.MIN_VALUE, getLong(first, LONG));
        assertEquals(
                127,
                getString(second, STRING).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThrows(NoSuchElementException.class, () -> getString(first, STRING));
        assertThrows(IllegalArgumentException.class, () -> getString(first, LONG));
        assertThrows(ReadOnlyBufferException.class, () -> first.encoded().setByte(0, (byte) 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> RpcOptions.builder().putString(STRING, "a".repeat(128)));
        assertThrows(
                IllegalArgumentException.class,
                () -> RpcOptions.builder().putString(STRING, "中".repeat(42) + "ab"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RpcOptions.builder().putString(STRING, String.valueOf((char) 0xd800)));
        var emoji = RpcOptions.builder().putString(STRING, "😀").build().metadata();
        assertEquals("😀", getString(emoji, STRING));
    }

    @Test
    void metadataUnknownPreservationAndDuplicateValidation() {
        var original = raw(new byte[] {0x13, (byte) 0x88, 2, 1, 2});
        var m = RpcMetadata.copyOf(original, 100);
        original.setByte(3, (byte) 9);
        assertEquals(1, m.encoded().getByte(3));
        assertTrue(m.contains((short) 5000));
        assertArrayEquals(new byte[] {1, 2}, m.get((short) 5000));
        assertThrows(
                RpcProtocolException.class,
                () ->
                        RpcMetadata.copyOf(
                                raw(new byte[] {0x13, (byte) 0x88, 0, 0x13, (byte) 0x88, 0}), 100));
    }

    @Test
    void arbitraryBytesArePreservedAndOnlyStringGetterValidatesUtf8() {
        for (byte[] value :
                List.of(
                        new byte[] {(byte) 0xc0, (byte) 0x80},
                        new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                        new byte[] {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
                        new byte[] {(byte) 0xe4, (byte) 0xb8})) {
            var m =
                    ALLOCATOR
                            .buffer(3 + value.length)
                            .writeShort(STRING)
                            .writeByte(value.length)
                            .writeBytes(value);
            var metadata = RpcMetadata.copyOf(m, 100);
            assertArrayEquals(value, metadata.get(STRING));
            assertThrows(IllegalArgumentException.class, () -> getString(metadata, STRING));
        }
    }
}
