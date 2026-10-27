package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.RPC_PROTOCOL_ERROR;

import cn.managame.Metadata;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultMessageSizeEstimator;
import io.netty.channel.MessageSizeEstimator;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.concurrent.Future;
import java.lang.System.Logger.Level;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Every connection's codec, one per connection after TLS and flush consolidation: bytes to frames and frames to
 * bytes, liveness, and the requests received and not answered yet. Each frame is its length as an int that excludes
 * itself, then, big-endian, its type byte and
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
 * Hello, Ping and Goodbye are the node's own. Bodies are not copied: an outgoing one follows its header as a buffer
 * of its own, an incoming one is a slice of what was read. Other outgoing messages pass through untouched.
 *
 * <p>Liveness costs a field write per read and write: every third of the idle timeout the codec looks at what
 * happened since it last looked. Having written nothing, it sends a Ping, once this side has sent its Hello; having
 * received nothing three times in a row, at least the idle timeout and at most a third more, it closes the
 * connection.
 *
 * <p>A request counts as unanswered, toward the node-wide count, from its decoding until its answer is encoded or
 * the connection closes; one that expired on the way is dropped instead. An answer too large for a frame goes out
 * as RPC_PROTOCOL_ERROR, so that the connection, and every other call on it, survives it.
 */
final class RpcFrames extends ByteToMessageDecoder implements ChannelOutboundHandler {
    /** The protocol versions this node speaks; two nodes speak the highest both do. */
    static final int MIN_VERSION = 1, MAX_VERSION = 1;
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
    private static final byte HELLO = 0, REQUEST = 1, NOTIFY = 2, RESPONSE = 3, PING = 4, GOODBYE = 5;
    /** Each frame's fixed part: the type byte, the fields and the metadata length. */
    private static final int REQUEST_HEADER = 32, NOTIFY_HEADER = 24, RESPONSE_HEADER = 11, HELLO_HEADER = 17, SIGNAL = 1;
    private static final int LENGTH_FIELD = 4;
    private final int maxFrameLength;
    private final Duration idleTimeout;
    private final LongAdder unanswered;
    /** The ids of the requests received and not answered yet. */
    private IntObjectHashMap<Boolean> waiting;
    private boolean read, wrote, helloSent;
    private int silentChecks;
    private Future<?> checks;

    /** {@code unanswered} is the node's count, shared by its connections. */
    RpcFrames(int maxFrameLength, Duration idleTimeout, LongAdder unanswered) {
        this.maxFrameLength = maxFrameLength;
        this.idleTimeout = idleTimeout;
        this.unanswered = unanswered;
    }

    /** Writes {@code frame}; false when the connection did not take it, which releases it. */
    static boolean send(Connection connection, Object frame) {
        if (connection.write(frame) == WriteStatus.ACCEPTED) return true;
        ReferenceCountUtil.release(frame);
        return false;
    }

    /** Whether {@code frame}, with its length, is no larger than {@code maxFrameLength}. */
    static boolean fits(Object frame, int maxFrameLength) { return size(frame) <= maxFrameLength; }

    /** The bytes {@code frame} takes on the wire, its length included; -1 for what is no RPC frame. */
    private static long size(Object frame) {
        return switch (frame) {
            case RpcRequest request -> LENGTH_FIELD + REQUEST_HEADER + request.metadata().bytes().length + (long) request.body().readableBytes();
            case RpcNotify notify -> LENGTH_FIELD + NOTIFY_HEADER + notify.metadata().bytes().length + (long) notify.body().readableBytes();
            case RpcResponse response -> LENGTH_FIELD + RESPONSE_HEADER + response.metadata().bytes().length + (long) response.body().readableBytes();
            case Hello hello -> LENGTH_FIELD + HELLO_HEADER + hello.metadata().bytes().length;
            case Ping ping -> LENGTH_FIELD + SIGNAL;
            case Goodbye goodbye -> LENGTH_FIELD + SIGNAL;
            default -> -1;
        };
    }

    /** The first frame each way on a connection; the side that dialed sends first. */
    record Hello(int minVersion, int maxVersion, int nodeId, long incarnation, Metadata metadata) {}

    /** Sent by a side that has written nothing for a while, so that the other side knows it is alive. */
    enum Ping { INSTANCE }

    /** Sent by a node that is closing: it takes nothing new. */
    enum Goodbye { INSTANCE }

    @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
        long every = idleTimeout.toNanos() / 3;
        checks = ctx.executor().scheduleWithFixedDelay(() -> check(ctx), every, every, TimeUnit.NANOSECONDS);
        super.channelActive(ctx);
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        read = true;
        super.channelRead(ctx, msg);
    }

    /** The requests that came on the connection can no longer be answered, even those decoded as it closes. */
    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (checks != null) checks.cancel(false);
        try {
            super.channelInactive(ctx);
        } finally {
            if (waiting != null) {
                unanswered.add(-waiting.size());
                waiting.clear();
            }
        }
    }

    private void check(ChannelHandlerContext ctx) {
        silentChecks = read ? 0 : silentChecks + 1;
        read = false;
        if (silentChecks >= 3) {
            LOG.log(Level.WARNING, "Closing RPC connection " + ctx.channel() + ": nothing received for " + idleTimeout);
            ctx.close();
        } else if (!wrote && helloSent) {
            ctx.writeAndFlush(signal(ctx.alloc(), PING), ctx.voidPromise());
        }
        wrote = false;
    }

    @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int start = in.readerIndex();
        if (in.readableBytes() < LENGTH_FIELD) return;
        int length = in.getInt(start);
        if (length < SIGNAL || length > maxFrameLength - LENGTH_FIELD) {
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
    private Object frame(ByteBuf in, int end) {
        byte type = in.readByte();
        int header = switch (type) {
            case HELLO -> HELLO_HEADER;
            case REQUEST -> REQUEST_HEADER;
            case NOTIFY -> NOTIFY_HEADER;
            case RESPONSE -> RESPONSE_HEADER;
            case PING, GOODBYE -> SIGNAL;
            default -> throw new CorruptedFrameException("Unknown RPC frame type " + type);
        };
        if (end - in.readerIndex() < header - 1) throw new CorruptedFrameException("RPC frame type " + type + " cut short");
        switch (type) {
            case HELLO -> {
                return new Hello(in.readUnsignedByte(), in.readUnsignedByte(), in.readInt(), in.readLong(), metadata(in, end));
            }
            case REQUEST -> {
                int id = in.readInt();
                long deadline = deadline(in.readInt());
                if (deadline != 0 && System.nanoTime() - deadline >= 0) return null;
                RpcRequest request = new RpcRequest(id, deadline, in.readInt(), in.readLong(), in.readByte(), in.readLong(),
                        metadata(in, end), body(in, end));
                if (waiting == null) waiting = new IntObjectHashMap<>();
                if (waiting.put(id, Boolean.TRUE) == null) unanswered.increment();
                return request;
            }
            case NOTIFY -> {
                return new RpcNotify(in.readInt(), in.readLong(), in.readByte(), in.readLong(), metadata(in, end), body(in, end));
            }
            case RESPONSE -> {
                return new RpcResponse(in.readInt(), in.readInt(), metadata(in, end), body(in, end));
            }
            case PING -> {
                return Ping.INSTANCE;
            }
            default -> {
                return Goodbye.INSTANCE;
            }
        }
    }

    /**
     * Encodes RPC frames, each into a header buffer followed by its body, which goes on as it is. Building a header
     * is all that can throw, and then the frame is released here, as nothing else will.
     */
    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        wrote = true;
        try {
            switch (msg) {
                case RpcRequest request -> send(ctx, request(ctx.alloc(), request), request.body(), promise);
                case RpcNotify notify -> send(ctx, notify(ctx.alloc(), notify), notify.body(), promise);
                case RpcResponse answer -> {
                    RpcResponse response = answered(ctx, answer);
                    send(ctx, response(ctx.alloc(), response), response.body(), promise);
                }
                case Hello hello -> {
                    helloSent = true;
                    ctx.write(hello(ctx.alloc(), hello), promise);
                }
                case Ping ping -> ctx.write(signal(ctx.alloc(), PING), promise);
                case Goodbye goodbye -> ctx.write(signal(ctx.alloc(), GOODBYE), promise);
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

    /** The answer is no longer waited for here; one too large for a frame is replaced by RPC_PROTOCOL_ERROR. */
    private RpcResponse answered(ChannelHandlerContext ctx, RpcResponse answer) {
        if (waiting != null && waiting.remove(answer.requestId()) != null) unanswered.decrement();
        if (fits(answer, maxFrameLength)) return answer;
        LOG.log(Level.WARNING, "The answer to request " + answer.requestId() + " on " + ctx.channel()
                + " is larger than the largest frame; RPC_PROTOCOL_ERROR goes instead");
        answer.release();
        return RpcResponse.failure(answer.requestId(), RPC_PROTOCOL_ERROR);
    }

    private static ByteBuf request(ByteBufAllocator alloc, RpcRequest request) {
        byte[] metadata = request.metadata().bytes();
        return start(alloc, REQUEST, REQUEST_HEADER, metadata, request.body())
                .writeInt(request.requestId()).writeInt(millisLeft(request.deadline())).writeInt(request.command())
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

    private static ByteBuf hello(ByteBufAllocator alloc, Hello hello) {
        byte[] metadata = hello.metadata().bytes();
        return start(alloc, HELLO, HELLO_HEADER, metadata, Unpooled.EMPTY_BUFFER)
                .writeByte(hello.minVersion()).writeByte(hello.maxVersion()).writeInt(hello.nodeId())
                .writeLong(hello.incarnation()).writeShort(metadata.length).writeBytes(metadata);
    }

    private static ByteBuf signal(ByteBufAllocator alloc, byte type) {
        return alloc.buffer(LENGTH_FIELD + SIGNAL).writeInt(SIGNAL).writeByte(type);
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
