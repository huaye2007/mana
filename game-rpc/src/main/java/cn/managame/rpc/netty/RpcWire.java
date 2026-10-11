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
    /**
     * Handshake proof fields of Wire v2. {@code nonce} is the sender's fresh random value;
     * {@code peerNonce} is zero in the initiating handshake and echoes the initiator's nonce in the reply.
     */
    public record HandshakeProof(long timestampMillis, byte[] nonce, byte[] peerNonce) {
        public static final HandshakeProof NONE = new HandshakeProof(0, new byte[RpcProtocol.NONCE], new byte[RpcProtocol.NONCE]);
        public HandshakeProof {
            if (nonce.length != RpcProtocol.NONCE || peerNonce.length != RpcProtocol.NONCE)
                throw new IllegalArgumentException("nonces must be 16 bytes");
            nonce = nonce.clone(); peerNonce = peerNonce.clone();
        }
        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public byte[] peerNonce() { return peerNonce.clone(); }
    }
    public record AuthenticatedHandshake(RpcHandshake identity, HandshakeProof proof) {}

    /** Unauthenticated handshake: zero proof fields and zero MAC. */
    public static ByteBuf encodeHandshake(RpcHandshake handshake) { return encodeHandshake(handshake, HandshakeProof.NONE, null); }
    /**
     * Wire v2 handshake. With a secret, the MAC is HMAC-SHA256 over every payload byte before it;
     * without one, the MAC is 32 zero bytes.
     */
    public static ByteBuf encodeHandshake(RpcHandshake handshake, HandshakeProof proof, byte[] secret) {
        int size = 1 + RpcProtocol.HANDSHAKE_PAYLOAD;
        ByteBuf frame = ByteBufAllocator.DEFAULT.buffer(4 + size, 4 + size).writeInt(size).writeByte(HANDSHAKE)
                .writeInt(RpcProtocol.MAGIC).writeShort(RpcProtocol.VERSION).writeInt(handshake.nodeId())
                .writeByte(handshake.slotId()).writeByte(handshake.slotCount()).writeLong(proof.timestampMillis)
                .writeBytes(proof.nonce).writeBytes(proof.peerNonce);
        frame.writeBytes(mac(secret, frame, 5, frame.writerIndex() - 5));
        return frame;
    }
    public static ByteBuf encodeHeartbeat() {
        return ByteBufAllocator.DEFAULT.buffer(5, 5).writeInt(1).writeByte(HEARTBEAT);
    }
    /** Reads an unauthenticated handshake payload after the type byte (MAC must be zero). */
    public static RpcHandshake decodeHandshake(ByteBuf frame) { return decodeHandshake(frame, null).identity(); }
    /**
     * Reads the payload after the type byte and verifies its MAC in constant time. A null secret requires
     * a zero MAC, so mismatched secret configuration fails instead of silently skipping authentication.
     * Freshness (timestamp window, nonce reuse, echoed nonce) is checked by the caller.
     */
    public static AuthenticatedHandshake decodeHandshake(ByteBuf frame, byte[] secret) {
        int start = frame.readerIndex();
        if (frame.readableBytes() != RpcProtocol.HANDSHAKE_PAYLOAD || frame.readInt() != RpcProtocol.MAGIC
                || frame.readUnsignedShort() != RpcProtocol.VERSION)
            throw new CorruptedFrameException("Invalid RPC handshake");
        int nodeId = frame.readInt(), slotId = frame.readUnsignedByte(), slotCount = frame.readUnsignedByte();
        long timestamp = frame.readLong();
        byte[] nonce = new byte[RpcProtocol.NONCE], peerNonce = new byte[RpcProtocol.NONCE], mac = new byte[RpcProtocol.MAC];
        frame.readBytes(nonce).readBytes(peerNonce);
        byte[] expected = mac(secret, frame, start, frame.readerIndex() - start);
        frame.readBytes(mac);
        if (!java.security.MessageDigest.isEqual(expected, mac)) throw new CorruptedFrameException("RPC handshake authentication failed");
        RpcHandshake identity;
        try { identity = new RpcHandshake(nodeId, slotId, slotCount); }
        catch (IllegalArgumentException invalid) { throw new CorruptedFrameException("Invalid RPC handshake identity"); }
        return new AuthenticatedHandshake(identity, new HandshakeProof(timestamp, nonce, peerNonce));
    }
    private static byte[] mac(byte[] secret, ByteBuf frame, int index, int length) {
        if (secret == null) return new byte[RpcProtocol.MAC];
        try {
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            hmac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
            hmac.update(frame.nioBuffer(index, length));
            return hmac.doFinal();
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
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

