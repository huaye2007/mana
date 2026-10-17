package cn.managame.router.node;

import cn.managame.core.Metadata;
import cn.managame.core.MetadataKey;
import cn.managame.rpc.message.*;
import io.netty.buffer.*;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.router.node.RouterWire.*;

class RouterWireTest {
    /** A Node configured with the default 4 MiB RPC frame limit. */
    static final int MAX_FRAME = 4 * 1024 * 1024;
    static final int MAX_INNER = MAX_FRAME - cn.managame.rpc.netty.RpcWire.MIN_REQUEST_FRAME - DATA_HEADER;
    @Test void goldenVectors() {
        ByteBuf attach = attach(5, 6).body(), detach = detach(5).body();
        RpcRequest data = data(DIRECT, 1, 2, 3, 4, 5, 0, new RpcRequest(44, 0, 9, 0, 0, null, null), MAX_INNER);
        RpcRequest located = locations(true, List.of(new Entry(7, 8, 9)));
        RpcRequest gone = unlocate(List.of(new Entry(7, 8, 0)));
        try {
            assertEquals("0501" + "0000000000000005" + "00000006", ByteBufUtil.hexDump(attach));
            assertEquals("0502" + "0000000000000005", ByteBufUtil.hexDump(detach));
            assertEquals("0504" + "01" + "0001" + "00000007" + "0000000000000008" + "00000009",
                    ByteBufUtil.hexDump(located.body()));
            assertEquals("0505" + "0001" + "00000007" + "0000000000000008", ByteBufUtil.hexDump(gone.body()));
            assertEquals("0503" + "01" + "00000001" + "0000000000000002" + "00000003" + "00000004" + "0000000000000005" + "00000000"
                            + "0000001c" + "03" + "0000002c" + "00000000" + "0000000000000009" + "00" + "0000000000000000" + "0000",
                    ByteBufUtil.hexDump(data.body()));
            assertEquals(9, data.routeKey());
            // Every control operation shares one routeKey, so they cannot overtake each other on their Slot.
            assertEquals(1, located.routeKey()); assertEquals(1, gone.routeKey());
        } finally { attach.release(); detach.release(); data.body().release(); located.body().release(); gone.body().release(); }
    }
    @Test void locationReportsRoundTripAndRejectMalformedCounts() {
        List<Entry> entries = List.of(new Entry(7, 8, 9), new Entry(10, 11, 12));
        RpcRequest report = locations(false, entries);
        try {
            ByteBuf b = report.body().duplicate();
            assertEquals(LOCATIONS, operation(b));
            Report decoded = locations(b);
            assertFalse(decoded.replace());
            assertEquals(entries, decoded.entries());
        } finally { report.body().release(); }
        // A complete set may be empty ("I have no attachments"); an upsert or a withdrawal may not.
        RpcRequest empty = locations(true, List.of());
        try {
            ByteBuf b = empty.body().duplicate(); operation(b);
            assertTrue(locations(b).replace());
            assertEquals(List.of(), locations(empty.body().duplicate().skipBytes(2)).entries());
        } finally { empty.body().release(); }
        assertThrows(IllegalStateException.class, () -> locations(false, List.of()));
        assertThrows(IllegalStateException.class, () -> unlocate(List.of()));
        // A declared count the payload does not cover, and a zero nodeId, are both rejected.
        ByteBuf truncated = Unpooled.buffer().writeByte(VERSION).writeByte(LOCATIONS).writeByte(1).writeShort(2).writeInt(7).writeLong(8).writeInt(9);
        try {
            ByteBuf b = truncated.duplicate(); operation(b);
            assertThrows(CorruptedFrameException.class, () -> locations(b));
        } finally { truncated.release(); }
        ByteBuf zeroNode = Unpooled.buffer().writeByte(VERSION).writeByte(UNLOCATE).writeShort(1).writeInt(0).writeLong(8);
        try {
            ByteBuf b = zeroNode.duplicate(); operation(b);
            assertThrows(CorruptedFrameException.class, () -> unlocate(b));
        } finally { zeroNode.release(); }
    }
    @Test void innerMessageFieldsAndBorrowedOwnershipSurviveTheFrame() {
        ByteBuf body = Unpooled.buffer().writeLong(42);
        RpcRequest encoded = data(DIRECT, 1, 11, 3, 2, 22, 0, new RpcRequest(44, 123, 99, 7, 777, null, body), MAX_INNER);
        assertEquals(0, body.refCnt());
        try {
            ByteBuf payload = encoded.body().duplicate(); assertEquals(DATA, operation(payload));
            Data d = data(payload); RpcRequest r = d.request();
            assertEquals(List.of(1, 11L, 3, 2, 22L, 123, 99L), List.of(d.source(), d.sourceEpoch(), d.sourceService(), d.target(), d.targetEpoch(), d.requestId(), d.routeKey()));
            assertEquals(123, r.requestId()); assertEquals(99, r.routeKey()); assertEquals(777, r.businessId()); assertEquals(42, r.body().readLong());
            assertEquals(1, encoded.body().refCnt());
            assertMalformed(encoded.body(), DATA_HEADER, 1);           // the length prefix must cover exactly the inner frame
            assertMalformed(encoded.body(), 2, RESPONSE);              // a response mode requires an inner response
            assertMalformed(encoded.body(), DATA_HEADER + 30, 0xffff); // Metadata longer than the frame
            assertMalformed(encoded.body(), 3, 0);                     // no source Node
        } finally { encoded.body().release(); }
    }
    private static void assertMalformed(ByteBuf frame, int index, int value) {
        ByteBuf copy = Unpooled.copiedBuffer(frame);
        try {
            switch (index) {
                case 2 -> copy.setByte(index, value);
                case 3, DATA_HEADER -> copy.setInt(index, value);
                default -> copy.setShort(index, value);
            }
            operation(copy);
            assertThrows(CorruptedFrameException.class, () -> data(copy));
        } finally { copy.release(); }
    }
    @Test void forwardingRewritesOnlyTheTargetAndTheCallId() {
        Metadata metadata = Metadata.builder().put(MetadataKey.longKey(1), 7L).build();
        for (int size : new int[]{8, 3 * COPY_LIMIT}) {
            byte[] bytes = new byte[size];
            for (int i = 0; i < size; i++) bytes[i] = (byte) i;
            RpcRequest encoded = data(DIRECT, 1, 11, 3, 2, 0, 0, new RpcRequest(44, 99, 7, 777, metadata, Unpooled.wrappedBuffer(bytes)), MAX_INNER);
            ByteBuf in = encoded.body().duplicate(); operation(in);
            Data source = data(in);
            RpcRequest forwarded = forward(source, 2, 22, 321);
            try {
                // A large inner frame is referenced from the received frame, never copied.
                assertEquals(size > COPY_LIMIT ? 2 : 1, encoded.body().refCnt());
                ByteBuf out = forwarded.body().duplicate(); assertEquals(DATA, operation(out));
                Data d = data(out);
                assertEquals(List.of(DIRECT, 1, 11L, 3, 2, 22L, 321, 99L),
                        List.of(d.mode(), d.source(), d.sourceEpoch(), d.sourceService(), d.target(), d.targetEpoch(), d.requestId(), d.routeKey()));
                assertEquals(99, forwarded.routeKey());
                RpcRequest r = d.request();
                assertEquals(List.of(44, 321, 99L, 7, 777L), List.of(r.command(), r.requestId(), r.routeKey(), r.businessIdType(), r.businessId()));
                assertEquals(7L, r.metadata().get(MetadataKey.longKey(1))); assertArrayEquals(bytes, ByteBufUtil.getBytes(r.body()));
                int skip = 13;   // length, type, command and call ID
                assertEquals(ByteBufUtil.hexDump(source.inner(), source.inner().readerIndex() + skip, source.inner().readableBytes() - skip),
                        ByteBufUtil.hexDump(d.inner(), d.inner().readerIndex() + skip, d.inner().readableBytes() - skip));
            } finally { forwarded.body().release(); }
            assertEquals(1, encoded.body().refCnt()); encoded.body().release();
        }
        RpcRequest response = data(RESPONSE, 2, 22, 2, 1, 11, 0, new RpcResponse(321, 5, null, Unpooled.wrappedBuffer(new byte[]{1, 2})), MAX_INNER);
        ByteBuf in = response.body().duplicate(); operation(in);
        RpcRequest back = forward(data(in), 1, 11, 0);
        try {
            ByteBuf out = back.body().duplicate(); operation(out);
            Data d = data(out); RpcResponse r = d.response();
            assertEquals(RESPONSE, d.mode()); assertEquals(321, r.requestId()); assertEquals(5, r.errorCode());
            assertArrayEquals(new byte[]{1, 2}, ByteBufUtil.getBytes(r.body()));
        } finally { back.body().release(); response.body().release(); }
    }
    @Test void theLargestInnerFrameStillFitsTheRpcFrameLimit() {
        RpcRequest largest = data(DIRECT, 1, 11, 3, 2, 0, 0, new RpcRequest(44, Unpooled.wrappedBuffer(new byte[MAX_INNER - 32])), MAX_INNER);
        ByteBuf frame = cn.managame.rpc.netty.RpcWire.encodeRequest(largest, 1, MAX_FRAME);
        try { assertEquals(MAX_FRAME, frame.readableBytes()); } finally { frame.release(); }
        ByteBuf body = Unpooled.wrappedBuffer(new byte[MAX_INNER - 31]);
        assertThrows(cn.managame.rpc.error.RpcEncodeException.class,
                () -> data(DIRECT, 1, 11, 3, 2, 0, 0, new RpcRequest(44, body), MAX_INNER));
        assertEquals(0, body.refCnt());
    }
    @Test void unknownVersionsAndBroadcastsWithoutAServiceAreRejected() {
        ByteBuf v3 = Unpooled.wrappedBuffer(new byte[]{3, 3});
        try { assertThrows(CorruptedFrameException.class, () -> operation(v3)); } finally { v3.release(); }
        // v5 is incompatible with v4: a v4 participant's frames are rejected rather than half-understood.
        ByteBuf v4 = Unpooled.wrappedBuffer(new byte[]{4, 3});
        try { assertThrows(CorruptedFrameException.class, () -> operation(v4)); } finally { v4.release(); }
        RpcRequest broadcast = data(BROADCAST, 1, 11, 3, 0, 0, 0, new RpcRequest(44, null), MAX_INNER);
        try {
            ByteBuf b = broadcast.body().duplicate(); operation(b);
            assertThrows(CorruptedFrameException.class, () -> data(b));
        } finally { broadcast.body().release(); }
    }
}
