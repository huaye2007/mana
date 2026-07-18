package cn.managame.gateway.rpc;

import cn.managame.common.context.Metadata;
import cn.managame.common.context.MetadataKeys;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;
import cn.managame.gateway.session.GatewaySessionManager;
import cn.managame.gateway.support.FakeConnection;
import cn.managame.rpc.RpcRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GatewayRpcMessageHandlerTest {
    @Test void successfulLoginBindsRoleAuthenticatesAndPreservesEnvelope() {
        GatewaySessionManager sessions = new GatewaySessionManager();
        FakeConnection connection = new FakeConnection(11, "1.2.3.4");
        GatewaySession session = new GatewaySession(11, connection, "1.2.3.4");
        sessions.add(session);
        GatewayRpcMessageHandler handler = new GatewayRpcMessageHandler(sessions, 1000);
        RpcRequest downlink = RpcRequest.oneway(1000)
                .busType(GatewayRpcProtocol.BUS_TYPE_SESSION).busId(11).routeKey(88)
                .body(new byte[]{9, 8})
                .metadata(new Metadata[]{
                        Metadata.ofLong(MetadataKeys.GW_SEQ, 7),
                        Metadata.ofLong(MetadataKeys.GW_CODE, 0),
                        Metadata.ofLong(MetadataKeys.GW_FLAGS, 2)});

        handler.handleUserMsg(null, downlink);

        assertTrue(session.isAuthenticated());
        assertSame(session, sessions.getByRoleId(88));
        GatewayPacket packet = (GatewayPacket) connection.writes().getFirst();
        assertEquals(7, packet.getSeq());
        assertEquals(2, packet.getFlags());
        assertArrayEquals(new byte[]{9, 8}, packet.getBody());
    }
}
