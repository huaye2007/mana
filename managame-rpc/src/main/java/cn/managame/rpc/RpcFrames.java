package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.RPC_PROTOCOL_ERROR;

import cn.managame.Metadata;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.util.ReferenceCountUtil;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The frames inside the length framing, big-endian, each starting with its type byte:
 * <ul>
 * <li>Hello (0): lowest and highest protocol version byte each, nodeId int, incarnation long, metadata;</li>
 * <li>Request (1): requestId int, time left in milliseconds int (0 for none), command int, routeKey long,
 * businessType byte, businessId long, metadata, body;</li>
 * <li>Notify (2): command int, routeKey long, businessType byte, businessId long, metadata, body;</li>
 * <li>Response (3): requestId int, code int, metadata, body;</li>
 * <li>Ping (4) and Goodbye (5): nothing.</li>
 * </ul>
 * Metadata is its length as an unsigned short, then its bytes. A later version may append to a Hello, which this
 * one ignores. Requests, notifies and responses are the public records, written to a connection as they are;
 * Hello, Ping and Goodbye are the node's own. Bodies are not copied: an outgoing one is appended to its header, an
 * incoming one is a slice of its frame. Other outgoing messages pass through untouched.
 *
 * <p>Encoding an answer tells the node its request was answered. An answer too large for a frame goes out as
 * RPC_PROTOCOL_ERROR instead, so that the connection, and every other call on it, survives it.
 */
final class RpcFrames extends MessageToMessageCodec<ByteBuf, Object> {
    /** The protocol versions this node speaks; two nodes speak the highest both do. */
    static final int MIN_VERSION = 1, MAX_VERSION = 1;
    private static final System.Logger LOG = System.getLogger("cn.managame.rpc");
    private static final byte HELLO = 0, REQUEST = 1, NOTIFY = 2, RESPONSE = 3, PING = 4, GOODBYE = 5;
    private static final int REQUEST_HEADER = 32, NOTIFY_HEADER = 24, RESPONSE_HEADER = 11, HELLO_HEADER = 17;
    /** The length the framing puts before every frame, which counts toward the largest frame. */
    private static final int LENGTH_FIELD = 4;
    private final RpcNode node;

    /** The first frame each way on a connection; the side that dialed sends first. */
    record Hello(int minVersion, int maxVersion, int nodeId, long incarnation, Metadata metadata) {}

    /** Sent by a side that has written nothing for a while, so that the other side knows it is alive. */
    enum Ping { INSTANCE }

    /** Sent by a node that is closing: it takes nothing new. */
    enum Goodbye { INSTANCE }

    RpcFrames(RpcNode node) { this.node = node; }

    /** Writes {@code frame}; false when the connection did not take it, which releases it. */
    static boolean send(Connection connection, Object frame) {
        if (connection.write(frame) == WriteStatus.ACCEPTED) return true;
        ReferenceCountUtil.release(frame);
        return false;
    }

    /** Whether {@code frame}, with the length before it, is no larger than {@code maxFrameLength}. */
    static boolean fits(Object frame, int maxFrameLength) {
        long size = LENGTH_FIELD + switch (frame) {
            case RpcRequest request -> REQUEST_HEADER + request.metadata().bytes().length + request.body().readableBytes();
            case RpcNotify notify -> NOTIFY_HEADER + notify.metadata().bytes().length + notify.body().readableBytes();
            case RpcResponse response -> RESPONSE_HEADER + response.metadata().bytes().length + response.body().readableBytes();
            default -> 1;
        };
        return size <= maxFrameLength;
    }

    @Override public boolean acceptOutboundMessage(Object msg) {
        return msg instanceof RpcRequest || msg instanceof RpcNotify || msg instanceof RpcResponse
                || msg instanceof Hello || msg instanceof Ping || msg instanceof Goodbye;
    }

    /** The body joins the header; the frame itself is released after this returns. */
    @Override protected void encode(ChannelHandlerContext ctx, Object frame, List<Object> out) {
        switch (frame) {
            case RpcRequest request -> {
                byte[] metadata = request.metadata().bytes();
                out.add(Unpooled.wrappedBuffer(ctx.alloc().buffer(REQUEST_HEADER + metadata.length).writeByte(REQUEST)
                        .writeInt(request.requestId()).writeInt(millisLeft(request.deadline()))
                        .writeInt(request.command()).writeLong(request.routeKey())
                        .writeByte(request.businessType()).writeLong(request.businessId())
                        .writeShort(metadata.length).writeBytes(metadata), request.body().retain()));
            }
            case RpcNotify notify -> {
                byte[] metadata = notify.metadata().bytes();
                out.add(Unpooled.wrappedBuffer(ctx.alloc().buffer(NOTIFY_HEADER + metadata.length).writeByte(NOTIFY)
                        .writeInt(notify.command()).writeLong(notify.routeKey())
                        .writeByte(notify.businessType()).writeLong(notify.businessId())
                        .writeShort(metadata.length).writeBytes(metadata), notify.body().retain()));
            }
            case RpcResponse answer -> {
                node.answered(ctx.channel(), answer.requestId());
                RpcResponse response = answer;
                if (!fits(answer, node.maxFrameLength())) {
                    LOG.log(Level.WARNING, "The answer to request " + answer.requestId() + " on " + ctx.channel()
                            + " is larger than the largest frame; RPC_PROTOCOL_ERROR goes instead");
                    response = RpcResponse.failure(answer.requestId(), RPC_PROTOCOL_ERROR);
                }
                byte[] metadata = response.metadata().bytes();
                out.add(Unpooled.wrappedBuffer(ctx.alloc().buffer(RESPONSE_HEADER + metadata.length).writeByte(RESPONSE)
                        .writeInt(response.requestId()).writeInt(response.code())
                        .writeShort(metadata.length).writeBytes(metadata), response.body().retain()));
            }
            case Hello hello -> {
                byte[] metadata = hello.metadata().bytes();
                out.add(ctx.alloc().buffer(HELLO_HEADER + metadata.length).writeByte(HELLO)
                        .writeByte(hello.minVersion()).writeByte(hello.maxVersion()).writeInt(hello.nodeId())
                        .writeLong(hello.incarnation()).writeShort(metadata.length).writeBytes(metadata));
            }
            case Goodbye goodbye -> out.add(ctx.alloc().buffer(1).writeByte(GOODBYE));
            default -> out.add(ctx.alloc().buffer(1).writeByte(PING));
        }
    }

    @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        byte type = in.readByte();
        switch (type) {
            case HELLO -> out.add(new Hello(in.readUnsignedByte(), in.readUnsignedByte(), in.readInt(), in.readLong(), metadata(in)));
            case REQUEST -> out.add(new RpcRequest(in.readInt(), deadline(in.readInt()), in.readInt(), in.readLong(),
                    in.readByte(), in.readLong(), metadata(in), body(in)));
            case NOTIFY -> out.add(new RpcNotify(in.readInt(), in.readLong(), in.readByte(), in.readLong(),
                    metadata(in), body(in)));
            case RESPONSE -> out.add(new RpcResponse(in.readInt(), in.readInt(), metadata(in), body(in)));
            case PING -> out.add(Ping.INSTANCE);
            case GOODBYE -> out.add(Goodbye.INSTANCE);
            default -> throw new CorruptedFrameException("Unknown RPC frame type " + type);
        }
    }

    /** The time a deadline leaves, at least a millisecond; 0 for no deadline. */
    private static int millisLeft(long deadline) {
        if (deadline == 0) return 0;
        long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        return (int) Math.clamp(left, 1, Integer.MAX_VALUE);
    }

    /** The deadline on this process's clock for the time left; never 0 for some time left. */
    private static long deadline(int millisLeft) {
        if (millisLeft <= 0) return 0;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millisLeft);
        return deadline != 0 ? deadline : 1;
    }

    private static Metadata metadata(ByteBuf in) {
        int length = in.readUnsignedShort();
        if (length == 0) return Metadata.empty();
        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        return Metadata.wrap(bytes);
    }

    private static ByteBuf body(ByteBuf in) {
        return in.isReadable() ? in.readRetainedSlice(in.readableBytes()) : Unpooled.EMPTY_BUFFER;
    }
}
