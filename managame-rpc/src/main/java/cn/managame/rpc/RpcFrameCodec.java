package cn.managame.rpc;

import cn.managame.Metadata;
import cn.managame.runtime.GameContext;
import cn.managame.rpc.RpcFrame.Failure;
import cn.managame.rpc.RpcFrame.Hello;
import cn.managame.rpc.RpcFrame.Ping;
import cn.managame.rpc.RpcFrame.Request;
import cn.managame.rpc.RpcFrame.Response;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageCodec;
import java.util.List;

/**
 * The frame layout inside the length framing, big-endian: a type byte, then
 * <ul>
 * <li>Hello: nodeId int;</li>
 * <li>Request: requestId int, then the caller's domain int, routingKey long, businessType byte, businessId long,
 * metadata length unsigned short and metadata bytes, then the body;</li>
 * <li>Response: requestId int, body;</li>
 * <li>Failure: requestId int, errorCode int;</li>
 * <li>Ping: nothing.</li>
 * </ul>
 * Bodies are not copied: an outgoing one is appended as is, an incoming one is a slice of the frame.
 */
@Sharable
final class RpcFrameCodec extends MessageToMessageCodec<ByteBuf, RpcFrame> {
    private static final byte HELLO = 0, REQUEST = 1, RESPONSE = 2, FAILURE = 3, PING = 4;

    @Override protected void encode(ChannelHandlerContext ctx, RpcFrame frame, List<Object> out) {
        switch (frame) {
            case Hello hello -> out.add(ctx.alloc().buffer(5).writeByte(HELLO).writeInt(hello.nodeId()));
            case Request request -> {
                GameContext caller = request.caller();
                byte[] metadata = caller.metadata().bytes();
                ByteBuf header = ctx.alloc().buffer(28 + metadata.length).writeByte(REQUEST).writeInt(request.requestId())
                        .writeInt(caller.domain()).writeLong(caller.routingKey()).writeByte(caller.businessType())
                        .writeLong(caller.businessId()).writeShort(metadata.length).writeBytes(metadata);
                out.add(Unpooled.wrappedBuffer(header, request.body()));
            }
            case Response response -> {
                ByteBuf header = ctx.alloc().buffer(5).writeByte(RESPONSE).writeInt(response.requestId());
                out.add(Unpooled.wrappedBuffer(header, response.body()));
            }
            case Failure failure -> out.add(ctx.alloc().buffer(9).writeByte(FAILURE)
                    .writeInt(failure.requestId()).writeInt(failure.errorCode()));
            case Ping ping -> out.add(ctx.alloc().buffer(1).writeByte(PING));
        }
    }

    @Override protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
        byte type = frame.readByte();
        switch (type) {
            case HELLO -> out.add(new Hello(frame.readInt()));
            case REQUEST -> {
                int requestId = frame.readInt(), domain = frame.readInt();
                long routingKey = frame.readLong();
                byte businessType = frame.readByte();
                long businessId = frame.readLong();
                byte[] metadata = new byte[frame.readUnsignedShort()];
                frame.readBytes(metadata);
                GameContext caller = new GameContext(domain, routingKey, businessType, businessId, Metadata.wrap(metadata));
                out.add(new Request(requestId, caller, frame.readRetainedSlice(frame.readableBytes())));
            }
            case RESPONSE -> out.add(new Response(frame.readInt(), frame.readRetainedSlice(frame.readableBytes())));
            case FAILURE -> out.add(new Failure(frame.readInt(), frame.readInt()));
            case PING -> out.add(Ping.INSTANCE);
            default -> throw new CorruptedFrameException("Unknown RPC frame type " + type);
        }
    }
}
