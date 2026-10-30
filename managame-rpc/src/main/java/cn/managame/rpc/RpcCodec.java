package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.RPC_PROTOCOL_ERROR;

import cn.managame.Metadata;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.netty.NettyConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultMessageSizeEstimator;
import io.netty.channel.MessageSizeEstimator;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import java.lang.System.Logger.Level;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Every connection's codec, one per connection after flush consolidation: bytes to frames and frames to bytes, and
 * the heartbeat. Each frame is its length as an int that excludes itself, then, big-endian, its type byte and
 * <ul>
 * <li>Handshake (0): protocol version byte, nodeId int, incarnation long, slot and slots unsigned short each,
 * metadata;</li>
 * <li>Heartbeat (1): nothing;</li>
 * <li>Request (2): requestId int, time left in milliseconds int (0 for none), command int, routeKey long,
 * businessType byte, businessId long, metadata, body;</li>
 * <li>Response (3): requestId int, code int, metadata, body;</li>
 * <li>Notify (4): command int, routeKey long, businessType byte, businessId long, metadata, body.</li>
 * </ul>
 * Metadata is its length as an unsigned short, then its bytes. Bodies are not copied: an outgoing one follows its
 * header as a buffer of its own, an incoming one is a slice of what was read. Other outgoing messages pass through
 * untouched. A frame larger than this node's largest closes the connection; an answer larger goes out as
 * RPC_PROTOCOL_ERROR instead. A request that expired on the way is dropped on arrival.
 *
 * <p>The heartbeat is the dialing side's: every heartbeat interval it sends a Heartbeat, which the other side
 * answers with one. Every interval of its own, on the node's time wheel, each side looks at whether anything
 * arrived since it last looked; nothing three times in a row closes the connection. Liveness costs a field write
 * per read.
 */
final class RpcCodec extends ByteToMessageDecoder implements ChannelOutboundHandler {
    /** The protocol version this node speaks; two nodes must speak the same. */
    static final int VERSION = 1;
    /** Heartbeat intervals with nothing received after which a connection is taken as dead. */
    static final int MISSED_HEARTBEATS = 3;
    /** The smallest largest frame a node may have. */
    static final int MIN_FRAME_LENGTH = 64;
    /** Sizes RPC frames as they will be encoded, so that back pressure counts them while they wait for their EventLoop. */
    static final MessageSizeEstimator SIZES = new MessageSizeEstimator() {
        private final Handle others = DefaultMessageSizeEstimator.DEFAULT.newHandle();
        private final Handle handle = msg -> {
            long size = size(msg);
            return size < 0 ? others.size(msg) : (int) Math.min(size, Integer.MAX_VALUE);
        };

        @Override public Handle newHandle() { return handle; }
    };
    private static final System.Logger LOG = System.getLogger("cn.managame.rpc");
    private static final byte HANDSHAKE = 0, HEARTBEAT = 1, REQUEST = 2, RESPONSE = 3, NOTIFY = 4;
    /** Each frame's fixed part: the type byte, the fields and the metadata length. */
    private static final int HANDSHAKE_HEADER = 20, REQUEST_HEADER = 32, RESPONSE_HEADER = 11, NOTIFY_HEADER = 24, HEARTBEAT_LENGTH = 1;
    private static final int LENGTH_FIELD = 4;
    private final int maxFrameLength;
    private final Timer timer;
    private final long heartbeatNanos;
    private final boolean dialing;
    private boolean read, handshakeSent;
    private int missedHeartbeats;
    private Timeout nextHeartbeat;

    RpcCodec(int maxFrameLength, Timer timer, long heartbeatMillis, boolean dialing) {
        this.maxFrameLength = maxFrameLength;
        this.timer = timer;
        this.dialing = dialing;
        heartbeatNanos = TimeUnit.MILLISECONDS.toNanos(heartbeatMillis);
    }

    /** The first frame each way on a connection; the side that dialed sends first and the other echoes its slot. */
    record Handshake(int version, int nodeId, long incarnation, int slot, int slots, Metadata metadata) {}

    /** Sent by the dialing side every heartbeat interval, and answered by the other side with one of its own. */
    enum Heartbeat { INSTANCE }

    /** Writes {@code frame} and returns what the connection did with it; unless it was ACCEPTED, releases it. */
    static WriteStatus write(Connection connection, Object frame) {
        WriteStatus status = connection.write(frame);
        if (status != WriteStatus.ACCEPTED) ReferenceCountUtil.release(frame);
        return status;
    }

    /**
     * Writes {@code frame} from any thread even when the connection is busy, as answers and heartbeats must go:
     * ACCEPTED, or INACTIVE once the connection has closed, which releases it. A connection other than network's
     * NettyConnection writes it as its {@code write} does.
     */
    static WriteStatus writeEvenIfBusy(Connection connection, Object frame) {
        WriteStatus status;
        if (connection instanceof NettyConnection netty) {
            Channel channel = netty.channel();
            status = channel.isActive() ? WriteStatus.ACCEPTED : WriteStatus.INACTIVE;
            if (status == WriteStatus.ACCEPTED) channel.writeAndFlush(frame, channel.voidPromise());
        } else {
            status = connection.write(frame);
        }
        if (status != WriteStatus.ACCEPTED) ReferenceCountUtil.release(frame);
        return status;
    }

    /** Whether {@code frame}, with its length, is no larger than {@code maxFrameLength}. */
    static boolean fits(Object frame, int maxFrameLength) { return size(frame) <= maxFrameLength; }

    /** The bytes {@code frame} takes on the wire, its length included; -1 for what is no RPC frame. */
    private static long size(Object frame) {
        return switch (frame) {
            case Call call -> LENGTH_FIELD + REQUEST_HEADER + call.request.metadata().bytes().length + (long) call.request.body().readableBytes();
            case RpcRequest request -> LENGTH_FIELD + REQUEST_HEADER + request.metadata().bytes().length + (long) request.body().readableBytes();
            case RpcNotify notify -> LENGTH_FIELD + NOTIFY_HEADER + notify.metadata().bytes().length + (long) notify.body().readableBytes();
            case RpcResponse response -> LENGTH_FIELD + RESPONSE_HEADER + response.metadata().bytes().length + (long) response.body().readableBytes();
            case Handshake handshake -> LENGTH_FIELD + HANDSHAKE_HEADER + handshake.metadata().bytes().length;
            case Heartbeat heartbeat -> LENGTH_FIELD + HEARTBEAT_LENGTH;
            default -> -1;
        };
    }

    @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
        scheduleHeartbeat(ctx);
        super.channelActive(ctx);
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        read = true;
        super.channelRead(ctx, msg);
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (nextHeartbeat != null) nextHeartbeat.cancel();
        super.channelInactive(ctx);
    }

    /** One heartbeat interval from now, on the time wheel, then on this connection's EventLoop. */
    private void scheduleHeartbeat(ChannelHandlerContext ctx) {
        try {
            nextHeartbeat = timer.newTimeout(timeout -> ctx.executor().execute(() -> heartbeat(ctx)), heartbeatNanos, TimeUnit.NANOSECONDS);
        } catch (IllegalStateException stopped) { // the node is closing
            ctx.close();
        }
    }

    /** Closes a connection on which nothing arrived for three intervals; otherwise the dialing side sends a heartbeat. */
    private void heartbeat(ChannelHandlerContext ctx) {
        if (!ctx.channel().isActive()) return;
        missedHeartbeats = read ? 0 : missedHeartbeats + 1;
        read = false;
        if (missedHeartbeats >= MISSED_HEARTBEATS) {
            LOG.log(Level.WARNING, "Closing RPC connection " + ctx.channel() + ": nothing received for "
                    + MISSED_HEARTBEATS + " heartbeat intervals of " + TimeUnit.NANOSECONDS.toMillis(heartbeatNanos) + " ms");
            ctx.close();
            return;
        }
        if (dialing && handshakeSent) ctx.writeAndFlush(heartbeatFrame(ctx.alloc()), ctx.voidPromise());
        scheduleHeartbeat(ctx);
    }

    @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int start = in.readerIndex();
        if (in.readableBytes() < LENGTH_FIELD) return;
        int length = in.getInt(start);
        if (length < HEARTBEAT_LENGTH || length > maxFrameLength - LENGTH_FIELD) {
            in.skipBytes(in.readableBytes());
            throw new CorruptedFrameException("An RPC frame of " + length + " bytes, the largest being " + (maxFrameLength - LENGTH_FIELD));
        }
        if (in.readableBytes() < LENGTH_FIELD + length) return;
        int end = start + LENGTH_FIELD + length;
        in.skipBytes(LENGTH_FIELD);
        Object frame = frame(in, end);
        in.readerIndex(end);
        if (frame != null) out.add(frame);
    }

    /** The frame between the reader index and {@code end}, or null for a request that expired on the way. */
    private static Object frame(ByteBuf in, int end) {
        byte type = in.readByte();
        int header = switch (type) {
            case HANDSHAKE -> HANDSHAKE_HEADER;
            case HEARTBEAT -> HEARTBEAT_LENGTH;
            case REQUEST -> REQUEST_HEADER;
            case RESPONSE -> RESPONSE_HEADER;
            case NOTIFY -> NOTIFY_HEADER;
            default -> throw new CorruptedFrameException("Unknown RPC frame type " + type);
        };
        if (end - in.readerIndex() < header - 1) throw new CorruptedFrameException("RPC frame type " + type + " cut short");
        switch (type) {
            case HANDSHAKE -> {
                return new Handshake(in.readUnsignedByte(), in.readInt(), in.readLong(), in.readUnsignedShort(), in.readUnsignedShort(),
                        metadata(in, end));
            }
            case HEARTBEAT -> {
                return Heartbeat.INSTANCE;
            }
            case REQUEST -> {
                int id = in.readInt();
                long deadline = deadline(in.readInt());
                if (deadline != 0 && System.nanoTime() - deadline >= 0) return null;
                return new RpcRequest(id, deadline, in.readInt(), in.readLong(), in.readByte(), in.readLong(),
                        metadata(in, end), body(in, end));
            }
            case RESPONSE -> {
                return new RpcResponse(in.readInt(), in.readInt(), metadata(in, end), body(in, end));
            }
            default -> {
                return new RpcNotify(in.readInt(), in.readLong(), in.readByte(), in.readLong(), metadata(in, end), body(in, end));
            }
        }
    }

    /**
     * Encodes RPC frames, each into a header buffer followed by its body, which goes on as it is. Building a header
     * is all that can throw, and then the frame is released here, as nothing else will.
     */
    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        try {
            switch (msg) {
                case Call call -> send(ctx, request(ctx.alloc(), call), call.request.body(), promise);
                case RpcNotify notify -> send(ctx, notify(ctx.alloc(), notify), notify.body(), promise);
                case RpcResponse answer -> {
                    RpcResponse response = fitted(ctx, answer);
                    send(ctx, response(ctx.alloc(), response), response.body(), promise);
                }
                case Handshake handshake -> {
                    handshakeSent = true;
                    ctx.write(handshake(ctx.alloc(), handshake), promise);
                }
                case Heartbeat heartbeat -> ctx.write(heartbeatFrame(ctx.alloc()), promise);
                default -> ctx.write(msg, promise);
            }
        } catch (RuntimeException | Error e) {
            ReferenceCountUtil.safeRelease(msg);
            throw e;
        }
    }

    /** The header, then the body unless it is empty; the header shares the promise only through the body's write. */
    private static void send(ChannelHandlerContext ctx, ByteBuf header, ByteBuf body, ChannelPromise promise) {
        if (body.isReadable()) {
            ctx.write(header, ctx.voidPromise());
            ctx.write(body, promise);
        } else {
            body.release();
            ctx.write(header, promise);
        }
    }

    /** The answer, or RPC_PROTOCOL_ERROR in its place when it is larger than the largest frame. */
    private RpcResponse fitted(ChannelHandlerContext ctx, RpcResponse answer) {
        if (fits(answer, maxFrameLength)) return answer;
        LOG.log(Level.WARNING, "The answer to request " + answer.requestId() + " on " + ctx.channel()
                + " is larger than the largest frame; RPC_PROTOCOL_ERROR goes instead");
        answer.release();
        return RpcResponse.failure(answer.requestId(), RPC_PROTOCOL_ERROR);
    }

    private static ByteBuf request(ByteBufAllocator alloc, Call call) {
        RpcRequest request = call.request;
        byte[] metadata = request.metadata().bytes();
        return start(alloc, REQUEST, REQUEST_HEADER, metadata, request.body())
                .writeInt(call.id).writeInt(millisLeft(call.deadline)).writeInt(request.command())
                .writeLong(request.routeKey()).writeByte(request.businessType()).writeLong(request.businessId())
                .writeShort(metadata.length).writeBytes(metadata);
    }

    private static ByteBuf notify(ByteBufAllocator alloc, RpcNotify notify) {
        byte[] metadata = notify.metadata().bytes();
        return start(alloc, NOTIFY, NOTIFY_HEADER, metadata, notify.body())
                .writeInt(notify.command()).writeLong(notify.routeKey())
                .writeByte(notify.businessType()).writeLong(notify.businessId())
                .writeShort(metadata.length).writeBytes(metadata);
    }

    private static ByteBuf response(ByteBufAllocator alloc, RpcResponse response) {
        byte[] metadata = response.metadata().bytes();
        return start(alloc, RESPONSE, RESPONSE_HEADER, metadata, response.body())
                .writeInt(response.requestId()).writeInt(response.code())
                .writeShort(metadata.length).writeBytes(metadata);
    }

    private static ByteBuf handshake(ByteBufAllocator alloc, Handshake handshake) {
        byte[] metadata = handshake.metadata().bytes();
        return start(alloc, HANDSHAKE, HANDSHAKE_HEADER, metadata, Unpooled.EMPTY_BUFFER)
                .writeByte(handshake.version()).writeInt(handshake.nodeId()).writeLong(handshake.incarnation())
                .writeShort(handshake.slot()).writeShort(handshake.slots())
                .writeShort(metadata.length).writeBytes(metadata);
    }

    private static ByteBuf heartbeatFrame(ByteBufAllocator alloc) {
        return alloc.buffer(LENGTH_FIELD + HEARTBEAT_LENGTH).writeInt(HEARTBEAT_LENGTH).writeByte(HEARTBEAT);
    }

    /** A header buffer with the frame's length, counting the body, and its type written. */
    private static ByteBuf start(ByteBufAllocator alloc, byte type, int header, byte[] metadata, ByteBuf body) {
        return alloc.buffer(LENGTH_FIELD + header + metadata.length)
                .writeInt(header + metadata.length + body.readableBytes()).writeByte(type);
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

    private static Metadata metadata(ByteBuf in, int end) {
        int length = in.readUnsignedShort();
        if (length > end - in.readerIndex()) throw new CorruptedFrameException("RPC metadata runs past its frame");
        if (length == 0) return Metadata.empty();
        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        return Metadata.wrap(bytes);
    }

    private static ByteBuf body(ByteBuf in, int end) {
        int length = end - in.readerIndex();
        return length > 0 ? in.readRetainedSlice(length) : Unpooled.EMPTY_BUFFER;
    }

    @Override public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) { ctx.bind(localAddress, promise); }

    @Override public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        ctx.connect(remoteAddress, localAddress, promise);
    }

    @Override public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) { ctx.disconnect(promise); }

    @Override public void close(ChannelHandlerContext ctx, ChannelPromise promise) { ctx.close(promise); }

    @Override public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) { ctx.deregister(promise); }

    @Override public void read(ChannelHandlerContext ctx) { ctx.read(); }

    @Override public void flush(ChannelHandlerContext ctx) { ctx.flush(); }
}
