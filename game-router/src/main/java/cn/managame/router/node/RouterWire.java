package cn.managame.router.node;

import cn.managame.rpc.message.*;
import cn.managame.rpc.netty.RpcWire;
import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.transport.RpcSendStatus;
import io.netty.buffer.*;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.ArrayList;
import java.util.List;

/** Router Profile v5 payloads inside ordinary RPC requests. Package-private protocol. */
final class RouterWire {
    private static final System.Logger LOG = System.getLogger(RouterWire.class.getName());
    static final int COMMAND = -2147483000, VERSION = 5;
    /** Service → Router announcements, routed data, and the Router → Router location reports. */
    static final int ATTACH = 1, DETACH = 2, DATA = 3, LOCATIONS = 4, UNLOCATE = 5;
    /** DATA modes. */
    static final int DIRECT = 1, BROADCAST = 2, RESPONSE = 3;
    /** Bytes before the inner frame of a DATA payload, and the inner frame size above which it is referenced, not copied. */
    static final int DATA_HEADER = 35, COPY_LIMIT = 2048;
    /** A uint16 counts the entries of one report, so one refresh cannot describe more attachments than this. */
    static final int MAX_ENTRIES = 65535;
    /** Every control operation shares one routeKey, so they use one Slot and arrive in the order they were sent. */
    private static final long CONTROL_ROUTE_KEY = 1;
    /** Offsets in an inner request frame (from its length prefix): the call ID and the routeKey that follows it. */
    private static final int CALL_ID = RpcWire.REQUEST_ID_OFFSET, CALL_ID_END = RpcWire.REQUEST_ROUTE_KEY_OFFSET;
    /** Smallest inner request/response frames: length prefix plus the fixed RPC fields. */
    private static final int REQUEST_MIN = RpcWire.MIN_REQUEST_FRAME, RESPONSE_MIN = RpcWire.MIN_RESPONSE_FRAME;

    /**
     * Largest inner frame for {@code node}: the outer RPC request (length prefix and fixed fields) plus the
     * Router header must fit the Node's single configured {@link RpcNode#maxFrameSize()}.
     */
    static int maxInner(RpcNode node) {
        return node.maxFrameSize() - RpcWire.MIN_REQUEST_FRAME - DATA_HEADER;
    }

    /**
     * A received DATA payload: the routing header plus the inner RPC frame (with its length prefix), borrowed
     * from the received body. A Router reads only the inner call ID and routeKey and passes the inner bytes on
     * unchanged; only the receiving service decodes them.
     */
    record Data(int mode, int source, long sourceEpoch, int sourceService, int target, long targetEpoch,
                int targetService, int requestId, long routeKey, ByteBuf inner) {
        /** Decodes the inner request; its body is borrowed like the frame. */
        RpcRequest request() { return RpcWire.decodeRequest(inner.duplicate().skipBytes(RpcWire.TYPE_OFFSET + 1)); }
        /** Decodes the inner response; its body is borrowed like the frame. */
        RpcResponse response() { return RpcWire.decodeResponse(requestId, inner.duplicate().skipBytes(RpcWire.RESPONSE_ID_OFFSET + 4)); }
    }

    /** One Node located on the reporting Router; {@code serviceId} is 0 in the entries of an Unlocate. */
    record Entry(int nodeId, long nodeEpoch, int serviceId) {}

    /** A received Locations report. {@code replace} makes the entries the reporter's complete set. */
    record Report(boolean replace, List<Entry> entries) {}

    static RpcRequest attach(long nodeEpoch, int serviceId) {
        return packet(CONTROL_ROUTE_KEY, Unpooled.buffer(14).writeByte(VERSION).writeByte(ATTACH).writeLong(nodeEpoch).writeInt(serviceId));
    }
    static RpcRequest detach(long nodeEpoch) {
        return packet(CONTROL_ROUTE_KEY, Unpooled.buffer(10).writeByte(VERSION).writeByte(DETACH).writeLong(nodeEpoch));
    }
    /** Reports the sending Router's attachments; {@code replace} marks the list as its complete set. */
    static RpcRequest locations(boolean replace, List<Entry> entries) {
        count(entries.size(), replace);
        ByteBuf b = Unpooled.buffer(5 + 16 * entries.size()).writeByte(VERSION).writeByte(LOCATIONS)
                .writeByte(replace ? 1 : 0).writeShort(entries.size());
        for (Entry e : entries) b.writeInt(e.nodeId()).writeLong(e.nodeEpoch()).writeInt(e.serviceId());
        return packet(CONTROL_ROUTE_KEY, b);
    }
    /** Withdraws reported attachments; each entry removes only an identical (nodeId, nodeEpoch). */
    static RpcRequest unlocate(List<Entry> entries) {
        count(entries.size(), false);
        ByteBuf b = Unpooled.buffer(4 + 12 * entries.size()).writeByte(VERSION).writeByte(UNLOCATE).writeShort(entries.size());
        for (Entry e : entries) b.writeInt(e.nodeId()).writeLong(e.nodeEpoch());
        return packet(CONTROL_ROUTE_KEY, b);
    }
    private static void count(int size, boolean mayBeEmpty) {
        if (size > MAX_ENTRIES) throw new IllegalStateException("Router report exceeds " + MAX_ENTRIES + " entries");
        if (size == 0 && !mayBeEmpty) throw new IllegalStateException("Router report needs at least one entry");
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

    /** Reads a Locations payload after its operation byte. */
    static Report locations(ByteBuf b) {
        need(b, 3);
        int flag = b.readUnsignedByte();
        if (flag > 1) throw new CorruptedFrameException("Invalid Locations replace flag");
        boolean replace = flag == 1;
        List<Entry> entries = entries(b, 16, replace);
        return new Report(replace, entries);
    }
    /** Reads an Unlocate payload after its operation byte; entry serviceId is 0. */
    static List<Entry> unlocate(ByteBuf b) {
        return entries(b, 12, false);
    }
    private static List<Entry> entries(ByteBuf b, int width, boolean mayBeEmpty) {
        need(b, 2);
        int size = b.readUnsignedShort();
        if (size == 0 && !mayBeEmpty) throw new CorruptedFrameException("Router report needs at least one entry");
        need(b, size * width);
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int nodeId = b.readInt();
            long nodeEpoch = b.readLong();
            int serviceId = width == 16 ? b.readInt() : 0;
            if (nodeId == 0 || nodeEpoch == 0 || (width == 16 && serviceId <= 0))
                throw new CorruptedFrameException("Invalid Router report entry");
            entries.add(new Entry(nodeId, nodeEpoch, serviceId));
        }
        end(b);
        return entries;
    }

    /**
     * A service's DATA request around {@code message}, whose inner frame may use at most {@code maxInner} bytes.
     * Consumes the message body, even on failure.
     */
    static RpcRequest data(int mode, int source, long sourceEpoch, int sourceService, int target, long targetEpoch,
                           int targetService, Object message, int maxInner) {
        long routeKey = message instanceof RpcRequest r ? r.routeKey() : 0;
        ByteBuf inner = switch (message) {
            case RpcRequest r -> RpcWire.encodeRequest(r, r.requestId(), maxInner);
            case RpcResponse r -> RpcWire.encodeResponse(r, maxInner);
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
        if (b.getUnsignedByte(at + RpcWire.TYPE_OFFSET) != (response ? RpcWire.RESPONSE : RpcWire.REQUEST))
            throw new CorruptedFrameException("Router mode/message mismatch");
        int fixed = response ? RESPONSE_MIN : REQUEST_MIN;
        if (length < fixed || b.getUnsignedShort(at + fixed - 2) > length - fixed)
            throw new CorruptedFrameException("Truncated inner RPC frame");
        return new Data(mode, source, sourceEpoch, sourceService, target, targetEpoch, targetService,
                b.getInt(at + (response ? RpcWire.RESPONSE_ID_OFFSET : CALL_ID)), response ? 0 : b.getLong(at + CALL_ID_END),
                b.slice(at, length));
    }
    /**
     * The same payload addressed to one exact Node incarnation. The inner frame is never decoded: it is copied
     * when small and otherwise referenced, and only a request's call ID is rewritten to {@code callId}. Passing
     * a frame to the next Router uses the call ID it already carries, so only the caller's own Router sets it.
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
