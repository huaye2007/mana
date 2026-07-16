package cn.managame.rpc;

import cn.managame.rpc.connection.RpcConnection;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RpcOnewayAdmissionTest {

    @Test
    void reportsMissingPeerAndMissingConnection() {
        RpcContainer container = new RpcContainer();
        assertFalse(container.tryOneway("logic", "1", RpcRequest.oneway(1)));

        container.getOrCreateRpcPeer("logic", "1");
        assertFalse(container.tryOneway("logic", "1", RpcRequest.oneway(1)));
    }

    @Test
    void reportsBackpressureRejection() {
        RpcContainer container = new RpcContainer();
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            RpcConnection connection = new RpcConnection(1, channel) {
                @Override public boolean isWritable() { return false; }
            };
            container.getOrCreateRpcPeer("logic", "1").add(connection);

            assertFalse(container.tryOneway("logic", "1", RpcRequest.oneway(1)));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
