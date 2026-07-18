package cn.managame.rpc;

import cn.managame.common.context.Metadata;
import cn.managame.common.context.MetadataKeys;
import cn.managame.rpc.connection.RpcConnection;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcHandshakeGateTest {

    @Test
    void rejectsBusinessMessageBeforeHandshake() {
        AtomicInteger handled = new AtomicInteger();
        RpcMessageHandler handler = countingHandler(handled);
        RpcServer server = new RpcServer(new RpcServerConfig().port(1).authToken("secret"), handler);
        EmbeddedChannel channel = new EmbeddedChannel(new RpcServerInternalHandler(server), handler);
        try {
            channel.writeInbound(RpcRequest.of(1));

            assertFalse(channel.isActive());
            assertEquals(0, handled.get());
        } finally {
            channel.finishAndReleaseAll();
            server.close(0);
        }
    }

    @Test
    void acceptsBusinessOnlyAfterValidSingleHandshake() {
        AtomicInteger handled = new AtomicInteger();
        RpcMessageHandler handler = countingHandler(handled);
        RpcServer server = new RpcServer(new RpcServerConfig().port(1).authToken("secret"), handler);
        EmbeddedChannel channel = new EmbeddedChannel(new RpcServerInternalHandler(server), handler);
        try {
            RpcRequest handshake = RpcRequest.oneway(RpcInternal.CMD_HANDSHAKE).metadata(new Metadata[]{
                    Metadata.ofString(MetadataKeys.RPC_AUTH_TOKEN, "secret"),
                    Metadata.ofString(MetadataKeys.RPC_SERVICE_NAME, "client"),
                    Metadata.ofString(MetadataKeys.RPC_SERVICE_ID, "1")
            });
            assertFalse(channel.writeInbound(handshake));
            assertTrue(channel.isActive());

            channel.writeInbound(RpcRequest.of(1));
            assertEquals(1, handled.get());

            channel.writeInbound(handshake);
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
            server.close(0);
        }
    }

    private static RpcMessageHandler countingHandler(AtomicInteger handled) {
        return new RpcMessageHandler() {
            @Override
            protected void handleUserMsg(RpcConnection connection, RpcRequest msg) {
                handled.incrementAndGet();
            }
        };
    }
}
