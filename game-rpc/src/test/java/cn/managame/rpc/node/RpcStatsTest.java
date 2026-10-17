package cn.managame.rpc.node;

import cn.managame.rpc.call.RpcCallback;
import cn.managame.rpc.message.RpcRequest;
import cn.managame.rpc.message.RpcResponse;
import cn.managame.rpc.netty.RpcWire;
import cn.managame.rpc.transport.RpcSendStatus;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static cn.managame.rpc.error.RpcErrorCodes.UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.*;

class RpcStatsTest extends RpcTestSupport {
    private static final RpcCallback<Object> CALLBACK = value -> {};

    @Test void peerStateIsWalkedOnDemandAndOrdinaryTrafficCountsNothing() throws Exception {
        Probe p = new Probe();
        try (RpcNode n = node(1, p)) {
            assertEquals(new RpcStats(0, 0, 0, 0, 0, 0, 0, 0, 0), n.stats());
            Fake c = bind(n, 2, 0, 1);
            RpcStats bound = n.stats();
            assertEquals(1, bound.peers());
            assertEquals(1, bound.peersConnected());
            assertEquals(1, bound.connections());
            assertEquals(1, bound.handshakesAccepted());
            assertEquals(0, bound.pendingCalls());

            n.call(2, new RpcRequest(1, null), CALLBACK);
            assertEquals(1, n.stats().pendingCalls());

            // The contract that keeps the readings free: ordinary sends, deliveries and completions move no
            // counter. Only the four gauges change, and they are walked from the Peer table when asked.
            RpcStats before = n.stats();
            for (int i = 0; i < 100; i++)
                assertEquals(RpcSendStatus.ACCEPTED, n.notify(2, new RpcRequest(5, Unpooled.buffer().writeByte(i))));
            receive(n, c, RpcWire.encodeRequest(new RpcRequest(9, 0, 0, 0, 0, null, null), 0, 1024));
            take(p.requests);
            int id = n.peers.get(2).pending.keys().nextElement();
            receive(n, c, RpcWire.encodeResponse(new RpcResponse(id, 0, null, null), 1024));
            take(p.responses);
            await(() -> n.stats().pendingCalls() == 0);
            RpcStats after = n.stats();
            assertEquals(before.callsRejected(), after.callsRejected());
            assertEquals(before.callsTimedOut(), after.callsTimedOut());
            assertEquals(before.sendsRefused(), after.sendsRefused());
            assertEquals(before.handshakesAccepted(), after.handshakesAccepted());
            assertEquals(before.reconnects(), after.reconnects());
        }
    }
    @Test void refusedCallsAndRefusedSendsAreCountedSeparately() throws Exception {
        Probe p = new Probe();
        try (RpcNode n = RpcNode.builder().nodeId(1).bindAddress(LOCAL).handler(p).maxPendingCalls(1).build()) {
            n.start();
            Fake c = bind(n, 2, 0, 1);
            n.call(2, new RpcRequest(1, null), CALLBACK);
            // The second call never reaches a connection: the Peer is already at maxPendingCalls.
            n.call(2, new RpcRequest(1, null), CALLBACK);
            assertEquals(UNAVAILABLE, take(p.failures));
            RpcStats refused = n.stats();
            assertEquals(1, refused.callsRejected());
            assertEquals(0, refused.sendsRefused());
            assertEquals(1, refused.pendingCalls());

            // A notify to a Peer whose only Slot refuses the write is a refused send, not a refused call.
            c.writable = false;
            assertEquals(RpcSendStatus.UNAVAILABLE, n.notify(2, new RpcRequest(5, Unpooled.buffer().writeByte(1))));
            assertEquals(1, n.stats().sendsRefused());
            assertEquals(1, n.stats().callsRejected());
        }
    }
}
