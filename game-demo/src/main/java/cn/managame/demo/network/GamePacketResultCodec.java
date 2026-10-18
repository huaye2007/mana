package cn.managame.demo.network;

import cn.managame.runtime.context.ClientHandlerContext;
import cn.managame.runtime.handler.ClientResultCodec;
import cn.managame.runtime.protocol.ProtocolDescriptor;
import cn.managame.runtime.protocol.ProtocolRegistry;
import org.apache.fory.ThreadSafeFory;

/**
 * Frames one business response as a GamePacket. The response command comes from the registered RESPONSE
 * descriptor of the returned type; the request's correlation comes from the context, not from Metadata.
 * A nonzero code carries a framework error and an empty body.
 */
public class GamePacketResultCodec implements ClientResultCodec {
    private static final byte[] NO_BODY = new byte[0];

    private final ThreadSafeFory fory;
    private final ProtocolRegistry protocols;

    public GamePacketResultCodec(ThreadSafeFory fory, ProtocolRegistry protocols) {
        this.fory = fory;
        this.protocols = protocols;
    }

    @Override
    public Object encode(ClientHandlerContext context, Object result) {
        ProtocolDescriptor response = protocols.get(result.getClass());
        if (response == null || response.type() != ProtocolDescriptor.Type.RESPONSE)
            throw new IllegalArgumentException("Not a registered RESPONSE: " + result.getClass());
        return packet(response.command(), context.requestId(), 0, fory.serialize(result));
    }

    @Override
    public Object encodeError(ClientHandlerContext context, int errorCode) {
        return packet(context.command(), context.requestId(), errorCode, NO_BODY);
    }

    /** Shared framing, also used before admission when dispatch itself is rejected. */
    public static GamePacket packet(int command, int seq, int code, byte[] body) {
        var packet = new GamePacket();
        packet.setCommand(command);
        packet.setSeq(seq);
        packet.setCode(code);
        packet.setBody(body);
        return packet;
    }
}
