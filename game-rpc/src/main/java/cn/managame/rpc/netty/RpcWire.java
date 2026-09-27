package cn.managame.rpc.netty;

import cn.managame.core.*;
import cn.managame.rpc.message.*;
import io.netty.buffer.*;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.concurrent.TimeUnit;

/**
 * Wire Profile v1 binding for standalone transport integration and RpcNode.
 * Encode methods consume one body reference, even on encoding failure.
 * Decode methods borrow the frame and return body slices with no extra reference.
 * A decoded body lives only as long as that frame, unless explicitly retained.
 */
public final class RpcWire {
    public static final int HANDSHAKE = 1, HEARTBEAT = 2, REQUEST = 3, RESPONSE = 4;
    private RpcWire() {}

    /** Adds frame decoding and heartbeat idle detection, in that order, to the pipeline. */
    public static void configurePipeline(ChannelPipeline pipeline, int maxFrameSize,
                                         long heartbeatIntervalMillis, long heartbeatTimeoutMillis) {
        if (maxFrameSize < 32) throw new IllegalArgumentException("maxFrameSize must be >= 32");
        if (heartbeatIntervalMillis <= 0 || heartbeatTimeoutMillis <= heartbeatIntervalMillis)
            throw new IllegalArgumentException("invalid heartbeat timing");
        // Complete frames, not trickled bytes, refresh read idle.
        pipeline.addLast("rpc-frame", new LengthFieldBasedFrameDecoder(maxFrameSize, 0, 4, 0, 4));
        pipeline.addLast("rpc-idle", new IdleStateHandler(heartbeatTimeoutMillis, heartbeatIntervalMillis,
                0, TimeUnit.MILLISECONDS));
    }

    public static ByteBuf encodeRequest(RpcRequest request, int assignedRequestId, int maxFrameSize) {
        return RpcEncoder.encode(request, assignedRequestId, maxFrameSize);
    }
    public static ByteBuf encodeResponse(RpcResponse response, int maxFrameSize) {
        return RpcEncoder.encode(response, maxFrameSize);
    }
    public static ByteBuf encodeHandshake(RpcHandshake handshake) {
        return ByteBufAllocator.DEFAULT.buffer(17, 17).writeInt(13).writeByte(HANDSHAKE)
                .writeInt(RpcProtocol.MAGIC).writeShort(RpcProtocol.VERSION).writeInt(handshake.nodeId())
                .writeByte(handshake.slotId()).writeByte(handshake.slotCount());
    }
    public static ByteBuf encodeHeartbeat() {
        return ByteBufAllocator.DEFAULT.buffer(5, 5).writeInt(1).writeByte(HEARTBEAT);
    }
    /** Reads payload after the type byte, rejecting trailing bytes. */
    public static RpcHandshake decodeHandshake(ByteBuf frame) {
        if (frame.readableBytes() != 12 || frame.readInt() != RpcProtocol.MAGIC
                || frame.readUnsignedShort() != RpcProtocol.VERSION)
            throw new CorruptedFrameException("Invalid RPC handshake");
        return new RpcHandshake(frame.readInt(), frame.readUnsignedByte(), frame.readUnsignedByte());
    }
    /** Reads request payload after the type byte. */
    public static RpcRequest decodeRequest(ByteBuf frame) {
        require(frame, 27);
        int command = frame.readInt(), id = frame.readInt();
        long route = frame.readLong();
        int businessType = frame.readUnsignedByte();
        long businessId = frame.readLong();
        Metadata metadata = metadata(frame);
        return new RpcRequest(command, id, route, businessType, businessId, metadata,
                frame.readSlice(frame.readableBytes()));
    }
    /** Reads response payload after type AND requestId; the node claims completion first. */
    public static RpcResponse decodeResponse(int requestId, ByteBuf frame) {
        require(frame, 6);
        int error = frame.readInt();
        Metadata metadata = metadata(frame);
        return new RpcResponse(requestId, error, metadata, frame.readSlice(frame.readableBytes()));
    }
    private static Metadata metadata(ByteBuf frame) {
        int length = frame.readUnsignedShort();
        require(frame, length);
        if (length == 0) return Metadatas.empty();
        byte[] bytes = new byte[length];
        frame.readBytes(bytes);
        return Metadatas.wrap(bytes);
    }
    private static void require(ByteBuf frame, int size) {
        if (frame.readableBytes() < size) throw new CorruptedFrameException("Truncated RPC payload");
    }
}

