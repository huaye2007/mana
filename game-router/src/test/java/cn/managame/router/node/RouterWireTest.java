package cn.managame.router.node;

import cn.managame.rpc.message.*;
import cn.managame.router.route.*;
import io.netty.buffer.*;
import io.netty.handler.codec.CorruptedFrameException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.router.node.RouterWire.*;

class RouterWireTest {
    @Test void streamGoldenVectors() {
        ByteBuf syncRequest = control(SYNC).writeLong(1).writeLong(2).writeLong(3);
        ByteBuf begin = sync(BEGIN, 1, 2, 0, 8).writeInt(3).writeInt(4);
        ByteBuf delta = sync(DELTA, 1, 2, 3, 0).writeShort(1).writeByte(BIND);
        binding(delta, new BindingKey(5, 6), new RouteBinding(7, 8));
        ByteBuf state = sync(STATE, 1, 2, 9, 0);
        try {
            assertEquals("0301" + "0000000000000001" + "0000000000000002" + "0000000000000003", ByteBufUtil.hexDump(syncRequest));
            assertEquals("0302" + "0000000000000001" + "0000000000000002" + "00000003" + "00000004", ByteBufUtil.hexDump(begin));
            assertEquals("0306" + "0000000000000001" + "0000000000000002" + "0000000000000003" + "0001" + "03"
                    + "00000005" + "0000000000000006" + "00000007" + "0000000000000008", ByteBufUtil.hexDump(delta));
            assertEquals("0307" + "0000000000000001" + "0000000000000002" + "0000000000000009", ByteBufUtil.hexDump(state));
            assertEquals(BEGIN, operation(begin));
            assertEquals(new SyncHeader(1, 2, 0), syncHeader(BEGIN, begin));
            assertEquals(3, begin.readInt()); assertEquals(4, begin.readInt()); end(begin);
            assertEquals(DELTA, operation(delta));
            assertEquals(new SyncHeader(1, 2, 3), syncHeader(DELTA, delta));
        } finally { syncRequest.release(); begin.release(); delta.release(); state.release(); }
    }
    @Test void unknownVersionAndZeroStreamIdentityAreRejected() {
        ByteBuf invalid = Unpooled.wrappedBuffer(new byte[]{2, 1});
        try { assertThrows(CorruptedFrameException.class, () -> operation(invalid)); assertEquals(1, invalid.refCnt()); }
        finally { invalid.release(); }
        ByteBuf zero = sync(END, 0, 1, 1, 0);
        try { operation(zero); assertThrows(CorruptedFrameException.class, () -> syncHeader(END, zero)); }
        finally { zero.release(); }
        ByteBuf truncated = control(DELTA).writeLong(1);
        try { operation(truncated); assertThrows(CorruptedFrameException.class, () -> syncHeader(DELTA, truncated)); }
        finally { truncated.release(); }
    }
    @Test void innerMessageFieldsOriginAndBorrowedOwnershipSurviveEnvelope() {
        ByteBuf body = Unpooled.buffer().writeLong(42);
        RpcRequest encoded = data(new Envelope(DIRECT, 101, 1, 11, 2, 22, 0, 0, 1,
                new RpcRequest(44, 123, 99, 7, 777, null, body)));
        assertEquals(0, body.refCnt());
        try {
            ByteBuf payload = encoded.body().duplicate(); assertEquals(DATA, operation(payload));
            Envelope e = data(payload); RpcRequest r = (RpcRequest) e.message();
            assertEquals(101, e.origin());
            assertEquals(1, e.source()); assertEquals(11, e.sourceEpoch()); assertEquals(2, e.target()); assertEquals(22, e.targetEpoch());
            assertEquals(123, r.requestId()); assertEquals(99, r.routeKey()); assertEquals(777, r.businessId()); assertEquals(42, r.body().readLong());
            assertEquals(1, encoded.body().refCnt());
            encoded.body().setInt(44, 1); // Prefix must exactly cover the remaining inner frame.
            ByteBuf malformed = encoded.body().duplicate(); operation(malformed);
            assertThrows(CorruptedFrameException.class, () -> data(malformed));
        } finally { encoded.body().release(); }
    }
}
