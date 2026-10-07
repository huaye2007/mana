package cn.managame.router.node;

import cn.managame.rpc.message.*;
import cn.managame.rpc.netty.RpcWire;
import cn.managame.router.route.*;
import io.netty.buffer.*;
import io.netty.handler.codec.CorruptedFrameException;

/** Router Profile v2 payloads inside ordinary RPC requests. Package-private protocol. */
final class RouterWire {
    static final int COMMAND = -2147483000, VERSION = 2, MAX_FRAME = 4 * 1024 * 1024;
    static final int HELLO = 1, BEGIN = 2, NODE = 3, ENTRY = 4, END = 5,
            ADD = 6, REMOVE = 7, BIND = 8, UNBIND = 9, REGISTER = 10,
            CLIENT_BIND = 11, CLIENT_UNBIND = 12, DETACH = 13, DATA = 14, DATA_ERROR = 15, VERIFY = 16;
    static final int DIRECT = 1, DYNAMIC = 2, BROADCAST = 3, RESPONSE = 4;
    record Envelope(int mode, int source, long sourceEpoch, int target, long targetEpoch,
                    int service, long key, int hops, Object message) {
        Envelope {
            if (!(message instanceof RpcRequest) && !(message instanceof RpcResponse))
                throw new IllegalArgumentException("Expected an RPC request or response");
        }
    }
    static ByteBuf control(int op) { return Unpooled.buffer().writeByte(VERSION).writeByte(op); }
    static RpcRequest packet(ByteBuf body) { return new RpcRequest(COMMAND, 1, 0, 0, null, body); }
    static int operation(ByteBuf body) {
        if (body == null || body.readableBytes() < 2 || body.readUnsignedByte() != VERSION)
            throw new CorruptedFrameException("Invalid Router Profile version");
        return body.readUnsignedByte();
    }
    static void end(ByteBuf body) {
        if (body.isReadable()) throw new CorruptedFrameException("Trailing Router control bytes");
    }
    static void node(ByteBuf b, NodeRegistration n) {
        b.writeInt(n.nodeId()).writeLong(n.nodeEpoch()).writeInt(n.serviceId());
    }
    static NodeRegistration node(ByteBuf b) { return new NodeRegistration(b.readInt(), b.readLong(), b.readInt()); }
    static void binding(ByteBuf b, BindingKey key, RouteBinding owner) {
        b.writeInt(key.serviceId()).writeLong(key.bindingKey()).writeInt(owner.nodeId()).writeLong(owner.nodeEpoch());
    }
    static BindingKey key(ByteBuf b) { return new BindingKey(b.readInt(), b.readLong()); }
    static RouteBinding owner(ByteBuf b) { return new RouteBinding(b.readInt(), b.readLong()); }

    /** Consumes message.body, including encoding/allocation failure. */
    static RpcRequest data(Envelope e) {
        ByteBuf inner = switch (e.message()) {
            case RpcRequest r -> RpcWire.encodeRequest(r, r.requestId(), MAX_FRAME - 80);
            case RpcResponse r -> RpcWire.encodeResponse(r, MAX_FRAME - 80);
            default -> throw new IllegalArgumentException("Unsupported message");
        };
        ByteBuf out = null;
        try {
            out = control(DATA);
            out.writeByte(e.mode()).writeInt(e.source()).writeLong(e.sourceEpoch())
                    .writeInt(e.target()).writeLong(e.targetEpoch()).writeInt(e.service())
                    .writeLong(e.key()).writeByte(e.hops()).writeBytes(inner);
            RpcRequest result = new RpcRequest(COMMAND, e.message() instanceof RpcRequest r ? r.routeKey() : 0, 0, 0, null, out);
            out = null;
            return result;
        } finally { inner.release(); if (out != null) out.release(); }
    }
    static Envelope data(ByteBuf b) {
        int mode = b.readUnsignedByte(), source = b.readInt(); long sourceEpoch = b.readLong();
        int target = b.readInt(); long targetEpoch = b.readLong();
        int service = b.readInt(); long key = b.readLong(); int hops = b.readUnsignedByte();
        if (mode < DIRECT || mode > RESPONSE || hops > 1 || source == 0 || sourceEpoch == 0)
            throw new CorruptedFrameException("Invalid routed identity/mode/hops");
        int size = b.readInt();
        if (size != b.readableBytes()) throw new CorruptedFrameException("Invalid inner frame length");
        Object message = switch (b.readUnsignedByte()) {
            case RpcWire.REQUEST -> RpcWire.decodeRequest(b);
            case RpcWire.RESPONSE -> RpcWire.decodeResponse(b.readInt(), b);
            default -> throw new CorruptedFrameException("Unsupported inner RPC message");
        };
        if ((mode == RESPONSE) != (message instanceof RpcResponse))
            throw new CorruptedFrameException("Router mode/message mismatch");
        return new Envelope(mode, source, sourceEpoch, target, targetEpoch, service, key, hops, message);
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
    static Envelope copy(Envelope e, int mode, int target, long epoch, int hops) {
        return new Envelope(mode, e.source(), e.sourceEpoch(), target, epoch, e.service(), e.key(), hops, retained(e.message()));
    }
    private RouterWire() {}
}
