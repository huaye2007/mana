package cn.managame.router.node;

import cn.managame.rpc.message.*;
import io.netty.buffer.*;
import io.netty.handler.codec.CorruptedFrameException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.router.node.RouterWire.*;

class RouterWireTest {
    @Test void verificationAndSnapshotRevisionGoldenVectors() {
        ByteBuf verify = control(VERIFY).writeLong(1).writeLong(2).writeLong(3);
        ByteBuf begin = control(BEGIN).writeLong(1).writeInt(2).writeInt(3).writeLong(4);
        try {
            assertEquals("0210000000000000000100000000000000020000000000000003", ByteBufUtil.hexDump(verify));
            assertEquals("0202000000000000000100000002000000030000000000000004", ByteBufUtil.hexDump(begin));
            assertEquals(VERIFY, operation(verify)); assertEquals(1, verify.readLong());
            assertEquals(2, verify.readLong()); assertEquals(3, verify.readLong()); end(verify);
        } finally { verify.release(); begin.release(); }
    }
    @Test void helloGoldenVectorAndUnknownVersion() {
        ByteBuf hello = control(HELLO).writeLong(0x0102030405060708L);
        try {
            assertEquals("02010102030405060708", ByteBufUtil.hexDump(hello));
            assertEquals(HELLO, operation(hello)); assertEquals(0x0102030405060708L, hello.readLong()); end(hello);
        } finally { hello.release(); }
        ByteBuf invalid = Unpooled.wrappedBuffer(new byte[]{1, 1});
        try { assertThrows(CorruptedFrameException.class, () -> operation(invalid)); assertEquals(1, invalid.refCnt()); }
        finally { invalid.release(); }
    }
    @Test void innerMessageFieldsAndBorrowedOwnershipSurviveEnvelope() {
        ByteBuf body = Unpooled.buffer().writeLong(42);
        RpcRequest encoded = data(new Envelope(DIRECT, 1, 11, 2, 22, 0, 0, 1,
                new RpcRequest(44, 123, 99, 7, 777, null, body)));
        assertEquals(0, body.refCnt());
        try {
            ByteBuf payload = encoded.body().duplicate(); assertEquals(DATA, operation(payload));
            Envelope e = data(payload); RpcRequest r = (RpcRequest) e.message();
            assertEquals(1, e.source()); assertEquals(11, e.sourceEpoch()); assertEquals(2, e.target()); assertEquals(22, e.targetEpoch());
            assertEquals(123, r.requestId()); assertEquals(99, r.routeKey()); assertEquals(777, r.businessId()); assertEquals(42, r.body().readLong());
            assertEquals(1, encoded.body().refCnt());
            encoded.body().setInt(40, 1); // Prefix must exactly cover the remaining inner frame.
            ByteBuf malformed = encoded.body().duplicate(); operation(malformed);
            assertThrows(CorruptedFrameException.class, () -> data(malformed));
        } finally { encoded.body().release(); }
    }
}
