package cn.managame.rpc.netty;

import cn.managame.rpc.transport.RpcNetworkConfig;
import cn.managame.rpc.transport.RpcTransport;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.*;
import cn.managame.rpc.core.*;

import io.netty.buffer.*;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class RpcTransportTest {
    static class Events implements RpcTransport.Listener {
        final AtomicInteger inbound = new AtomicInteger(), outbound = new AtomicInteger();
        final TestSignal<byte[]> received = new TestSignal<>();

        public NetworkHandler newInboundHandler() {
            inbound.incrementAndGet();
            return (connection, message) ->
                    received.complete(ByteBufUtil.getBytes((ByteBuf) message));
        }

        public NetworkHandler newOutboundHandler() {
            outbound.incrementAndGet();
            return (connection, message) -> {};
        }

        public void onWriteFailure(Connection connection, Throwable failure) {}
    }

    NettyRpcTransport transport() {
        return new NettyRpcTransport(
                new RpcNetworkConfig(
                        10, InetSocketAddress.createUnresolved("127.0.0.1", 0), 1024, false),
                null);
    }

    TestSignal<Connection> connect(RpcTransport transport, InetSocketAddress address) {
        var result = new TestSignal<Connection>();
        transport.connect(
                address,
                new ConnectCallback() {
                    public void onSuccess(Connection connection) {
                        result.complete(connection);
                    }

                    public void onFailure(Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
        return result;
    }

    @Test
    void listenerCreatesPerConnectionHandlersAndWriteOwnsFraming() throws Exception {
        try (var transport = transport()) {
            assertNotNull(transport.allocator());
            assertNull(transport.localAddress());
            var events = new Events();
            transport.start(events);
            var address = transport.localAddress();
            assertTrue(address.getPort() > 0);
            assertThrows(IllegalStateException.class, () -> transport.start(events));
            var connection =
                    connect(
                                    transport,
                                    InetSocketAddress.createUnresolved(
                                            "127.0.0.1", address.getPort()))
                            .get(3, TimeUnit.SECONDS);
            ByteBuf frame = Unpooled.buffer().writeBytes(new byte[] {10, 20, 30});
            try {
                assertEquals(RpcTransport.Submission.ACCEPTED, transport.write(connection, frame));
                assertArrayEquals(
                        new byte[] {10, 20, 30}, events.received.get(3, TimeUnit.SECONDS));
                assertEquals(0, frame.readerIndex());
            } finally {
                frame.release();
            }
            assertEquals(1, events.inbound.get());
            assertEquals(1, events.outbound.get());
        }
    }

    @Test
    void connectBeforeStartAndAfterCloseFailsThroughCallback() {
        var transport = transport();
        var address = InetSocketAddress.createUnresolved("127.0.0.1", 12345);
        assertThrows(ExecutionException.class, () -> connect(transport, address).get(0, TimeUnit.NANOSECONDS));
        transport.close();
        transport.close();
        assertThrows(ExecutionException.class, () -> connect(transport, address).get(0, TimeUnit.NANOSECONDS));
        assertThrows(IllegalStateException.class, () -> transport.start(new Events()));
    }
}
