package cn.managame.gateway.rpc;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.router.BackendDirectory;
import cn.managame.gateway.session.GatewaySession;
import cn.managame.gateway.support.FakeConnection;
import cn.managame.registry.api.ServiceInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PacketForwarderTest {

    @Test
    void returnsServerBusyWhenRpcDeliveryIsRejected() {
        BackendDirectory backends = new BackendDirectory();
        backends.upsert(ServiceInstance.builder().name("logic").id("node-1")
                .address("127.0.0.1").port(9001).build());
        FakeConnection connection = new FakeConnection(7, "127.0.0.1:10000");
        GatewaySession session = new GatewaySession(7, connection, connection.getRemoteAddress());
        PacketForwarder forwarder = new PacketForwarder((service, id, request) -> false,
                backends, (current, packet) -> "logic", 1001);

        forwarder.forward(session, GatewayPacket.of(1001, 9, 0, new byte[]{1}));

        assertNull(session.getBackendServiceId("logic"));
        assertEquals(1, connection.writes().size());
        GatewayPacket response = (GatewayPacket) connection.writes().getFirst();
        assertEquals(GatewayErrorCode.SERVER_BUSY, response.getCode());
        assertEquals(9, response.getSeq());
    }
}
