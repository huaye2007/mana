package com.github.huaye2007.mana.rpc.client;

import com.github.huaye2007.mana.rpc.RpcClientConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcClientConfigTest {

    @Test
    void defaultConfigIsValid() {
        assertDoesNotThrow(() -> new RpcClientConfig().validate());
    }

    @Test
    void rejectsNonPositiveConnectionSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new RpcClientConfig().connectionSize(0).validate());
    }

    @Test
    void rejectsIdleTimeoutNotGreaterThanHeartbeatInterval() {
        // 读空闲超时 <= 心跳间隔会让连接在两次心跳之间被误判死亡
        assertThrows(IllegalArgumentException.class,
                () -> new RpcClientConfig().heartbeatIntervalSeconds(10).idleTimeoutSeconds(10).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new RpcClientConfig().heartbeatIntervalSeconds(10).idleTimeoutSeconds(5).validate());
    }

    @Test
    void heartbeatDisabledSkipsIdleConstraint() {
        assertDoesNotThrow(() -> new RpcClientConfig()
                .heartbeatIntervalSeconds(0).idleTimeoutSeconds(1).validate());
    }

    @Test
    void rejectsMaxBackoffSmallerThanInitial() {
        assertThrows(IllegalArgumentException.class, () -> new RpcClientConfig()
                .reconnectInitialBackoffMillis(5000).reconnectMaxBackoffMillis(1000).validate());
    }

    @Test
    void reconnectDisabledSkipsBackoffConstraint() {
        assertDoesNotThrow(() -> new RpcClientConfig()
                .reconnectEnabled(false).reconnectInitialBackoffMillis(0).validate());
    }


    @Test
    void rejectsInvalidWriteBufferWaterMarks() {
        // 高水位必须大于低水位，且不允许只设一边
        assertThrows(IllegalArgumentException.class,
                () -> new RpcClientConfig().writeBufferWaterMark(64 * 1024, 32 * 1024).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new RpcClientConfig().writeBufferWaterMark(0, 64 * 1024).validate());
        assertDoesNotThrow(() -> new RpcClientConfig().writeBufferWaterMark(32 * 1024, 128 * 1024).validate());
        assertDoesNotThrow(() -> new RpcClientConfig().validate()); // 双 0 = Netty 默认
    }

    
}
