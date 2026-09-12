package cn.managame.rpc;

import io.netty.buffer.ByteBuf;

import java.util.Objects;

/** Uniform codec execution, validation and ownership of encoded frames; no call lifecycle. */
final class RpcCodecHandler {
    private final RpcCodec codec;
    private final RpcLimits limits;

    RpcCodecHandler(RpcCodec codec, RpcLimits limits) {
        this.codec = codec;
        this.limits = limits;
    }

    RpcMessage decode(ByteBuf frame) {
        var message = Objects.requireNonNull(codec.decode(frame), "Decoded message");
        RpcChecks.message(message, limits);
        return message;
    }

    ByteBuf encode(RpcMessage message) {
        RpcChecks.message(message, limits);
        ByteBuf frame = Objects.requireNonNull(codec.encode(message), "Encoded frame");
        if (!frame.isReadable() || frame.readableBytes() > limits.maxMessageBytes()) {
            frame.release();
            throw new RpcProtocolException("Encoded frame size");
        }
        return frame;
    }
}
