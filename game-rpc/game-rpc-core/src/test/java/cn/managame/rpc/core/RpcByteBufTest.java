package cn.managame.rpc.core;

import cn.managame.rpc.protocol.RpcLimits;
import cn.managame.rpc.protocol.RpcMetadata;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRequest;
import cn.managame.rpc.protocol.RpcRouteMessage;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.*;

import org.junit.jupiter.api.Test;

class RpcByteBufTest {
    private final RpcTestSupport.TestCodec wire =
            new RpcTestSupport.TestCodec(PooledByteBufAllocator.DEFAULT, RpcLimits.DEFAULT);

    @Test
    void readableRegionIsBorrowedAndMetadataOutlivesPooledFrame() {
        ByteBuf body = PooledByteBufAllocator.DEFAULT.buffer().writeInt(1234);
        ByteBuf frame =
                wire.request(7, 8, RpcOptions.builder().putLong((short) 1, 99).build(), body);
        ByteBuf input = PooledByteBufAllocator.DEFAULT.buffer().writeZero(5).writeBytes(frame);
        input.readerIndex(5);
        try {
            var request = (RpcRequest) wire.decode(input);
            assertEquals(5, input.readerIndex());
            assertEquals(7, request.command());
            assertEquals(8, request.requestId());
            assertEquals(1234, ((ByteBuf) request.body()).readInt());
            assertTrue(((ByteBuf) request.body()).isReadOnly());
            assertEquals(1, input.refCnt());
            assertTrue(input.release());
            assertEquals(0, ((ByteBuf) request.body()).refCnt());
            assertEquals(99, request.metadata().getLong((short) 1));
            assertFalse(request.metadata().encoded().release());
            assertEquals(99, request.metadata().getLong((short) 1));
        } finally {
            if (input.refCnt() > 0) input.release();
            frame.release();
            body.release();
        }
    }

    @Test
    void metadataCopiesReadableBytesWithoutTakingSourceOwnership() {
        ByteBuf source = PooledByteBufAllocator.DEFAULT.directBuffer().writeByte(9).writeInt(42);
        source.readerIndex(1);
        try {
            var metadata = new RpcMetadata().put((short) 1, source);
            assertEquals(1, source.readerIndex());
            assertEquals(5, source.writerIndex());
            assertEquals(1, source.refCnt());
            source.setInt(1, 100);
            assertTrue(source.release());
            assertEquals(42, metadata.getInt((short) 1));
            var view = metadata.getBuffer((short) 1);
            assertEquals(42, view.readInt());
            assertEquals(42, metadata.getInt((short) 1));
            assertFalse(view.release());
            assertEquals(42, metadata.getInt((short) 1));
        } finally {
            if (source.refCnt() > 0) source.release();
        }
    }

    @Test
    void frameEncodingPreservesInputsAndRouteSlicesShareOnlyFrameOwnership() {
        ByteBuf body = PooledByteBufAllocator.DEFAULT.buffer().writeByte(9).writeLong(17);
        body.readerIndex(1);
        ByteBuf request = null, route = null;
        try {
            request = wire.request(3, 1, RpcOptions.DEFAULT, body);
            assertEquals(1, body.readerIndex());
            assertEquals(1, body.refCnt());
            route = wire.route(10, 20, request);
            assertEquals(0, request.readerIndex());
            assertEquals(2, request.refCnt());
            var decoded = (RpcRouteMessage) wire.decode(route);
            assertTrue(decoded.inner().isReadOnly());
            assertEquals(
                    17, ((ByteBuf) ((RpcRequest) wire.decode(decoded.inner())).body()).readLong());
            assertEquals(1, route.refCnt());
            assertTrue(route.release());
            assertEquals(0, decoded.inner().refCnt());
            assertEquals(1, request.refCnt());
        } finally {
            if (route != null && route.refCnt() > 0) route.release();
            if (request != null) request.release();
            body.release();
        }
    }
}
