package cn.managame.rpc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RpcResponse} 行为与防御性边界。重点验证 {@link RpcResponse#bodyAsBytes()}
 * 对非 byte[] body 给出明确错误（H3：避免裸强转 ClassCastException 被 IO/timer 线程吞）。
 */
class RpcResponseTest {

    private static final byte SERIAL = 1;

    @Test
    void bodyAsBytesReturnsByteArray() {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        RpcResponse response = RpcResponse.of(1L, SERIAL, body);

        assertArrayEquals(body, response.bodyAsBytes());
    }

    @Test
    void bodyAsBytesReturnsNullWhenBodyNull() {
        RpcResponse response = RpcResponse.of(1L, SERIAL, null);

        assertNull(response.body());
        assertNull(response.bodyAsBytes());
    }

    @Test
    void bodyAsBytesRejectsNonByteArrayWithClearMessage() {
        // 模拟误用：旁路构造的 response（mock/测试/错误用法）body 是 POJO。
        // 旧实现此处 decodeBody 会抛 ClassCastException 并被 safeOnException 吞成不直观的错；
        // 现在应抛 GameRpcException 并携带实际类型，便于排查。
        RpcResponse response = new RpcResponse(1L, 0, SERIAL, "not-a-byte-array", null);

        GameRpcException ex = assertThrows(GameRpcException.class, response::bodyAsBytes);
        assertTrue(ex.getMessage().contains("byte[]"), "message should mention byte[]");
        assertTrue(ex.getMessage().contains(String.class.getName()),
                "message should mention the actual body type, got: " + ex.getMessage());
    }

    @Test
    void errorResponseHasNullBodyAndBodyAsBytes() {
        RpcResponse response = RpcResponse.error(1L, 42, "boom");

        assertEquals(42, response.code());
        assertTrue(!response.isSuccess());
        assertNull(response.body());
        assertNull(response.bodyAsBytes());
    }
}
