package cn.managame.router.node;

import cn.managame.rpc.message.*;
import cn.managame.rpc.netty.RpcWire;
import cn.managame.router.route.*;
import io.netty.buffer.*;
import io.netty.handler.codec.CorruptedFrameException;

/** Router Profile v3 payloads inside ordinary RPC requests. Package-private protocol. */
final class RouterWire {
    static final int COMMAND = -2147483000, VERSION = 3, MAX_FRAME = 4 * 1024 * 1024;
    // Router ↔ Router synchronization, always sent as RPC notify on the single Router Slot.
    static final int SYNC = 1, BEGIN = 2, NODES = 3, BINDINGS = 4, END = 5, DELTA = 6, STATE = 7;
    // Service ↔ home Router controls (RPC call) and routed traffic.
    static final int REGISTER = 10, CLIENT_BIND = 11, CLIENT_UNBIND = 12, DETACH = 13, DATA = 14, DATA_ERROR = 15, VERIFY = 16;
    // DELTA operation kinds.
    static final int ADD_NODE = 1, REMOVE_NODE = 2, BIND = 3, UNBIND = 4;
    static final int DIRECT = 1, DYNAMIC = 2, BROADCAST = 3, RESPONSE = 4;
    /** Entries per snapshot chunk or operations per DELTA frame. */
    static final int CHUNK = 1024;
    static final int NODE_BYTES = 16, BINDING_BYTES = 24;
    /** Router bytes before the inner frame of a DATA request, and the size above which it is not copied. */
    static final int DATA_HEADER = 44, COPY_LIMIT = 2048;

    /**
     * origin is the Router the calling service is attached to; responses and routed errors return there
     * directly, so no replica of the caller's Router is needed on the return path. 0 means not yet set.
     */
    record Envelope(int mode, int origin, int source, long sourceEpoch, int target, long targetEpoch,
                    int service, long key, int hops, Object message) {
        Envelope {
            if (!(message instanceof RpcRequest) && !(message instanceof RpcResponse))
                throw new IllegalArgumentException("Expected an RPC request or response");
        }
    }
    static ByteBuf control(int op) { return Unpooled.buffer().writeByte(VERSION).writeByte(op); }
    static ByteBuf control(int op, int capacity) { return Unpooled.buffer(capacity).writeByte(VERSION).writeByte(op); }
    static RpcRequest packet(ByteBuf body) { return new RpcRequest(COMMAND, 1, 0, 0, null, body); }
    static int operation(ByteBuf body) {
        if (body == null || body.readableBytes() < 2 || body.readUnsignedByte() != VERSION)
            throw new CorruptedFrameException("Invalid Router Profile version");
        return body.readUnsignedByte();
    }
    static void end(ByteBuf body) {
        if (body.isReadable()) throw new CorruptedFrameException("Trailing Router control bytes");
    }
    static void need(ByteBuf body, int bytes) {
        if (body.readableBytes() < bytes) throw new CorruptedFrameException("Truncated Router control");
    }
    static void node(ByteBuf b, NodeRegistration n) {
        b.writeInt(n.nodeId()).writeLong(n.nodeEpoch()).writeInt(n.serviceId());
    }
    static NodeRegistration node(ByteBuf b) {
        need(b, NODE_BYTES);
        try { return new NodeRegistration(b.readInt(), b.readLong(), b.readInt()); }
        catch (IllegalArgumentException invalid) { throw new CorruptedFrameException("Invalid Node registration"); }
    }
    static void binding(ByteBuf b, BindingKey key, RouteBinding owner) {
        b.writeInt(key.serviceId()).writeLong(key.bindingKey()).writeInt(owner.nodeId()).writeLong(owner.nodeEpoch());
    }
    static BindingKey key(ByteBuf b) {
        need(b, 12);
        try { return new BindingKey(b.readInt(), b.readLong()); }
        catch (IllegalArgumentException invalid) { throw new CorruptedFrameException("Invalid binding key"); }
    }
    static RouteBinding owner(ByteBuf b) {
        need(b, 12);
        try { return new RouteBinding(b.readInt(), b.readLong()); }
        catch (IllegalArgumentException invalid) { throw new CorruptedFrameException("Invalid binding owner"); }
    }

    /** Header shared by every synchronization frame after the operation byte. */
    record SyncHeader(long epoch, long session, long seq) {}
    static ByteBuf sync(int op, long epoch, long session, long seq, int capacity) {
        ByteBuf b = control(op, capacity + 26).writeLong(epoch).writeLong(session);
        return op == BEGIN ? b : b.writeLong(seq);
    }
    static SyncHeader syncHeader(int op, ByteBuf b) {
        need(b, op == BEGIN ? 16 : 24);
        long epoch = b.readLong(), session = b.readLong(), seq = op == BEGIN ? 0 : b.readLong();
        if (epoch == 0 || session == 0) throw new CorruptedFrameException("Invalid Router stream identity");
        return new SyncHeader(epoch, session, seq);
    }

    /** Consumes message.body, including encoding/allocation failure. */
    static RpcRequest data(Envelope e) {
        ByteBuf inner = switch (e.message()) {
            case RpcRequest r -> RpcWire.encodeRequest(r, r.requestId(), MAX_FRAME - 80);
            case RpcResponse r -> RpcWire.encodeResponse(r, MAX_FRAME - 80);
            default -> throw new IllegalArgumentException("Unsupported message");
        };
        ByteBuf out = null;
        try {
            // Small frames are copied behind the header; large ones are referenced instead of copied.
            boolean share = inner.readableBytes() > COPY_LIMIT;
            out = Unpooled.buffer(DATA_HEADER + (share ? 0 : inner.readableBytes())).writeByte(VERSION).writeByte(DATA);
            out.writeByte(e.mode()).writeInt(e.origin()).writeInt(e.source()).writeLong(e.sourceEpoch())
                    .writeInt(e.target()).writeLong(e.targetEpoch()).writeInt(e.service())
                    .writeLong(e.key()).writeByte(e.hops());
            if (share) { out = Unpooled.wrappedBuffer(out, inner); inner = null; }
            else out.writeBytes(inner);
            RpcRequest result = new RpcRequest(COMMAND, e.message() instanceof RpcRequest r ? r.routeKey() : 0, 0, 0, null, out);
            out = null;
            return result;
        } finally { if (inner != null) inner.release(); if (out != null) out.release(); }
    }
    static Envelope data(ByteBuf b) {
        need(b, 1 + 4 + 4 + 8 + 4 + 8 + 4 + 8 + 1 + 5);
        int mode = b.readUnsignedByte(), origin = b.readInt(), source = b.readInt(); long sourceEpoch = b.readLong();
        int target = b.readInt(); long targetEpoch = b.readLong();
        int service = b.readInt(); long key = b.readLong(); int hops = b.readUnsignedByte();
        if (mode < DIRECT || mode > RESPONSE || hops > 1 || source == 0 || sourceEpoch == 0)
            throw new CorruptedFrameException("Invalid routed identity/mode/hops");
        int size = b.readInt();
        if (size != b.readableBytes()) throw new CorruptedFrameException("Invalid inner frame length");
        Object message = switch (b.readUnsignedByte()) {
            case RpcWire.REQUEST -> RpcWire.decodeRequest(b);
            case RpcWire.RESPONSE -> { need(b, 4); yield RpcWire.decodeResponse(b.readInt(), b); }
            default -> throw new CorruptedFrameException("Unsupported inner RPC message");
        };
        if ((mode == RESPONSE) != (message instanceof RpcResponse))
            throw new CorruptedFrameException("Router mode/message mismatch");
        return new Envelope(mode, origin, source, sourceEpoch, target, targetEpoch, service, key, hops, message);
    }
    static Object retained(Object message) {
        return switch (message) {
            case RpcRequest r -> new RpcRequest(r.command(), r.requestId(), r.routeKey(), r.businessIdType(),
                    r.businessId(), r.metadata(), r.body() == null ? null : r.body().retain());
            case RpcResponse r -> new RpcResponse(r.requestId(), r.errorCode(), r.metadata(),
                    r.body() == null ? null : r.body().retain());
            default -> throw new IllegalArgumentException("Unsupported message");
        };
    }
    static Envelope copy(Envelope e, int mode, int origin, int target, long epoch, int hops) {
        return new Envelope(mode, origin, e.source(), e.sourceEpoch(), target, epoch, e.service(), e.key(), hops, retained(e.message()));
    }
    private RouterWire() {}
}
