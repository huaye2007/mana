package cn.managame.router.node;

import cn.managame.rpc.message.*;
import cn.managame.rpc.netty.RpcWire;
import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.transport.RpcSendStatus;
import io.netty.buffer.*;
import io.netty.handler.codec.CorruptedFrameException;

/** Router Profile v4 payloads inside ordinary RPC requests. Package-private protocol. */
final class RouterWire {
    private static final System.Logger LOG = System.getLogger(RouterWire.class.getName());
    static final int COMMAND = -2147483000, VERSION = 4, MAX_FRAME = 4 * 1024 * 1024;
    /** Service → Router notifies that announce or withdraw the Node on this connection, and routed data. */
    static final int ATTACH = 1, DETACH = 2, DATA = 3;
    /** DATA modes. */
    static final int DIRECT = 1, BROADCAST = 2, RESPONSE = 3;
    /** Bytes before the inner frame of a DATA payload, and the inner frame size above which it is referenced, not copied. */
    static final int DATA_HEADER = 35, COPY_LIMIT = 2048;
    /** Largest inner frame: the outer RPC request (4-byte length, 28 fixed bytes) and the Router header must fit MAX_FRAME. */
    static final int MAX_INNER = MAX_FRAME - 32 - DATA_HEADER;
    /** Attach and Detach share one routeKey, so they use one Slot and arrive in the order they were sent. */
    private static final long CONTROL_ROUTE_KEY = 1;
    /** Offsets in an inner request frame (from its length prefix): the call ID, and the end of the call ID. */
    private static final int CALL_ID = 9, CALL_ID_END = 13;
    /** Smallest inner request/response frames: length prefix plus the fixed RPC fields. */
    private static final int REQUEST_MIN = 32, RESPONSE_MIN = 15;

    /**
     * A received DATA payload: the routing header plus the inner RPC frame (with its length prefix), borrowed
     * from the received body. A Router reads only the inner call ID and routeKey and passes the inner bytes on
     * unchanged; only the receiving service decodes them.
     */
    record Data(int mode, int source, long sourceEpoch, int sourceService, int target, long targetEpoch,
                int targetService, int requestId, long routeKey, ByteBuf inner) {
        /** Decodes the inner request; its body is borrowed like the frame. */
        RpcRequest request() { return RpcWire.decodeRequest(inner.duplicate().skipBytes(5)); }
        /** Decodes the inner response; its body is borrowed like the frame. */
        RpcResponse response() { return RpcWire.decodeResponse(requestId, inner.duplicate().skipBytes(9)); }
    }

    static RpcRequest attach(long nodeEpoch, int serviceId) {
        return packet(CONTROL_ROUTE_KEY, Unpooled.buffer(14).writeByte(VERSION).writeByte(ATTACH).writeLong(nodeEpoch).writeInt(serviceId));
    }
    static RpcRequest detach(long nodeEpoch) {
        return packet(CONTROL_ROUTE_KEY, Unpooled.buffer(10).writeByte(VERSION).writeByte(DETACH).writeLong(nodeEpoch));
    }
    static int operation(ByteBuf body) {
        if (body == null || body.readableBytes() < 2 || body.readUnsignedByte() != VERSION)
            throw new CorruptedFrameException("Invalid Router Profile version");
        return body.readUnsignedByte();
    }
    static void need(ByteBuf body, int bytes) {
        if (body.readableBytes() < bytes) throw new CorruptedFrameException("Truncated Router payload");
    }
    static void end(ByteBuf body) {
        if (body.isReadable()) throw new CorruptedFrameException("Trailing Router payload bytes");
    }

    /** A service's DATA request around {@code message}. Consumes the message body, even on failure. */
    static RpcRequest data(int mode, int source, long sourceEpoch, int sourceService, int target, long targetEpoch,
                           int targetService, Object message) {
        long routeKey = message instanceof RpcRequest r ? r.routeKey() : 0;
        ByteBuf inner = switch (message) {
            case RpcRequest r -> RpcWire.encodeRequest(r, r.requestId(), MAX_INNER);
            case RpcResponse r -> RpcWire.encodeResponse(r, MAX_INNER);
            default -> throw new IllegalArgumentException("Expected an RPC request or response");
        };
        try {
            int length = inner.readableBytes();
            boolean share = length > COPY_LIMIT;
            ByteBuf out = header(mode, source, sourceEpoch, sourceService, target, targetEpoch, targetService, share ? 0 : length);
            if (!share) return packet(routeKey, out.writeBytes(inner));
            RpcRequest result = packet(routeKey, Unpooled.wrappedBuffer(out, inner));
            inner = null;
            return result;
        } finally { if (inner != null) inner.release(); }
    }
    /** Reads a DATA payload after its operation byte, validating the header and the inner frame's fixed fields. */
    static Data data(ByteBuf b) {
        need(b, DATA_HEADER - 2 + 5);
        int mode = b.readUnsignedByte(), source = b.readInt(); long sourceEpoch = b.readLong(); int sourceService = b.readInt();
        int target = b.readInt(); long targetEpoch = b.readLong(); int targetService = b.readInt();
        if (mode < DIRECT || mode > RESPONSE || source == 0 || sourceEpoch == 0 || sourceService <= 0)
            throw new CorruptedFrameException("Invalid routed source or mode");
        if (mode == BROADCAST ? targetService <= 0 : target == 0 || targetService != 0 || (mode == RESPONSE && targetEpoch == 0))
            throw new CorruptedFrameException("Invalid routed target");
        int at = b.readerIndex(), length = b.readableBytes();
        if (b.getInt(at) != length - 4) throw new CorruptedFrameException("Invalid inner frame length");
        boolean response = mode == RESPONSE;
        if (b.getUnsignedByte(at + 4) != (response ? RpcWire.RESPONSE : RpcWire.REQUEST))
            throw new CorruptedFrameException("Router mode/message mismatch");
        int fixed = response ? RESPONSE_MIN : REQUEST_MIN;
        if (length < fixed || b.getUnsignedShort(at + fixed - 2) > length - fixed)
            throw new CorruptedFrameException("Truncated inner RPC frame");
        return new Data(mode, source, sourceEpoch, sourceService, target, targetEpoch, targetService,
                b.getInt(at + (response ? 5 : CALL_ID)), response ? 0 : b.getLong(at + CALL_ID_END), b.slice(at, length));
    }
    /**
     * The same payload addressed to one exact Node incarnation. The inner frame is never decoded: it is copied
     * when small and otherwise referenced, and only a request's call ID is rewritten to {@code callId}.
     */
    static RpcRequest forward(Data d, int target, long targetEpoch, int callId) {
        ByteBuf inner = d.inner();
        int length = inner.readableBytes(), copied = length > COPY_LIMIT ? CALL_ID_END : length;
        ByteBuf out = header(d.mode(), d.source(), d.sourceEpoch(), d.sourceService(), target, targetEpoch, d.targetService(), copied)
                .writeBytes(inner, inner.readerIndex(), copied);
        if (d.mode() != RESPONSE) out.setInt(DATA_HEADER + CALL_ID, callId);
        if (copied < length) out = Unpooled.wrappedBuffer(out, inner.retainedSlice(inner.readerIndex() + copied, length - copied));
        return packet(d.routeKey(), out);
    }
    private static ByteBuf header(int mode, int source, long sourceEpoch, int sourceService, int target, long targetEpoch,
                                  int targetService, int innerBytes) {
        return Unpooled.buffer(DATA_HEADER + innerBytes).writeByte(VERSION).writeByte(DATA).writeByte(mode)
                .writeInt(source).writeLong(sourceEpoch).writeInt(sourceService)
                .writeInt(target).writeLong(targetEpoch).writeInt(targetService);
    }
    static RpcRequest packet(long routeKey, ByteBuf body) { return new RpcRequest(COMMAND, routeKey, 0, 0, null, body); }

    /**
     * Releases a body that RPC did not take. RPC takes a body once it accepts the call's arguments and keeps it
     * after a successful return (a large body is referenced by the frame being written), so callers release
     * only when the call threw; by then a taken body has already been released.
     */
    static void release(ByteBuf body) { if (body != null && body.refCnt() > 0) body.release(); }
    /** A send that never throws; the message body is always consumed. */
    static RpcSendStatus notify(RpcNode rpc, int id, RpcRequest message) {
        try { return rpc.notify(id, message); }
        catch (RuntimeException error) {
            release(message.body());
            LOG.log(System.Logger.Level.WARNING, "Router send failed", error);
            return RpcSendStatus.UNAVAILABLE;
        }
    }
    private RouterWire() {}
}
