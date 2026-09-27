package cn.managame.rpc.netty;

import cn.managame.core.*;
import cn.managame.rpc.error.RpcEncodeException;
import cn.managame.rpc.message.*;
import io.netty.buffer.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.*;
import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class RpcWireTest {
    @Test void goldenVectorsAndBorrowedBodies() {
        ByteBuf body = Unpooled.buffer().writeByte(99).writeByte(12);
        body.readByte();
        body.retain();
        ByteBuf encoded = RpcWire.encodeRequest(new RpcRequest(0x01020304, 10, 2, 11, null, body), 9, 1024);
        assertEquals(1, body.refCnt());
        assertEquals(1, body.readerIndex());
        assertEquals("0000001d030102030400000009000000000000000a02000000000000000b00000c",
                ByteBufUtil.hexDump(encoded));
        encoded.skipBytes(5);
        RpcRequest decoded = RpcWire.decodeRequest(encoded);
        assertEquals(9, decoded.requestId());
        assertEquals(12, decoded.body().getUnsignedByte(0));
        assertEquals(encoded.refCnt(), decoded.body().refCnt());
        encoded.release();
        assertEquals(0, decoded.body().refCnt());
        body.release();

        ByteBuf handshake = RpcWire.encodeHandshake(new RpcHandshake(0x01020304, 2, 3));
        assertEquals("0000000d01474e53520001010203040203", ByteBufUtil.hexDump(handshake));
        handshake.skipBytes(5);
        assertEquals(new RpcHandshake(0x01020304, 2, 3), RpcWire.decodeHandshake(handshake));
        handshake.release();
        ByteBuf heartbeat = RpcWire.encodeHeartbeat();
        assertEquals("0000000102", ByteBufUtil.hexDump(heartbeat));
        heartbeat.release();
        ByteBuf response = RpcWire.encodeResponse(new RpcResponse(9, 2006, null, null), 1024);
        assertEquals("0000000b0400000009000007d60000", ByteBufUtil.hexDump(response));
        response.skipBytes(9);
        assertEquals(2006, RpcWire.decodeResponse(9, response).errorCode());
        response.release();
    }

    @Test void unsignedIdsAndMetadataRoundTrip() {
        Metadata metadata = Metadatas.builder().put(MetadataKeys.stringKey(7), "hello").build();
        ByteBuf encoded = RpcWire.encodeRequest(new RpcRequest(-1, Long.MIN_VALUE, 255, -1, metadata, null), -1, 1024);
        encoded.skipBytes(5);
        RpcRequest decoded = RpcWire.decodeRequest(encoded);
        assertEquals(-1, decoded.requestId());
        assertEquals(Long.MIN_VALUE, decoded.routeKey());
        assertEquals("hello", decoded.metadata().get(MetadataKeys.stringKey(7)));
        encoded.release();
        assertEquals("hello", decoded.metadata().get(MetadataKeys.stringKey(7)));
    }

    @Test void encodingFailuresConsumeBodyAndRespectTotalSize() {
        ByteBuf body = Unpooled.buffer().writeByte(1);
        assertThrows(RpcEncodeException.class,
                () -> RpcWire.encodeRequest(new RpcRequest(1, body), 1, 32));
        assertEquals(0, body.refCnt());
        ByteBuf exact = RpcWire.encodeRequest(new RpcRequest(1, null), 1, 32);
        assertEquals(32, exact.readableBytes());
        exact.release();
        Metadata broken = new Metadata() {
            public <T> T get(MetadataKey<T> key) { return null; }
            public boolean contains(MetadataKey<?> key) { return false; }
            public boolean isEmpty() { return false; }
            public byte[] bytes() { return new byte[] {0}; }
        };
        ByteBuf second = Unpooled.buffer().writeByte(3);
        assertThrows(RpcEncodeException.class,
                () -> RpcWire.encodeResponse(new RpcResponse(1, 0, broken, second), 1024));
        assertEquals(0, second.refCnt());
    }

    @Test void fragmentationCoalescingAndOversizeDeclaration() {
        EmbeddedChannel channel = new EmbeddedChannel(new LengthFieldBasedFrameDecoder(32, 0, 4, 0, 4));
        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {0, 0})));
        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {0, 1, 2, 0, 0, 0, 1, 2})));
        ByteBuf first = channel.readInbound(), second = channel.readInbound();
        assertEquals(2, first.readUnsignedByte());
        assertEquals(2, second.readUnsignedByte());
        first.release(); second.release();
        assertThrows(TooLongFrameException.class,
                () -> channel.writeInbound(Unpooled.buffer().writeInt(33)));
        channel.finishAndReleaseAll();
    }

    @Test void malformedFieldsRejected() {
        for (String hex : new String[] {"000000000000000000000000", "474e53520001000000000001",
                "474e53520001000000010201", "474e5352000100000001000100"}) {
            ByteBuf b = Unpooled.wrappedBuffer(HexFormat.of().parseHex(hex));
            try { assertThrows(RuntimeException.class, () -> RpcWire.decodeHandshake(b)); }
            finally { b.release(); }
        }
        ByteBuf negative = Unpooled.buffer().writeInt(-1).writeShort(0);
        assertThrows(IllegalArgumentException.class, () -> RpcWire.decodeResponse(1, negative));
        negative.release();
        ByteBuf truncated = Unpooled.buffer().writeInt(0).writeShort(5).writeByte(1);
        assertThrows(CorruptedFrameException.class, () -> RpcWire.decodeResponse(1, truncated));
        truncated.release();
        ByteBuf duplicate = Unpooled.buffer().writeInt(0).writeShort(8)
                .writeShort(1).writeShort(0).writeShort(1).writeShort(0);
        assertThrows(IllegalArgumentException.class, () -> RpcWire.decodeResponse(1, duplicate));
        duplicate.release();
    }
}

