package cn.managame.rpc.protocol;

import io.netty.buffer.*;

import java.util.Objects;

/** Signed, big-endian default wire codec; treats business body bytes as opaque. */
public class DefaultRpcCodec implements RpcCodec {
    private final ByteBufAllocator allocator;
    private final RpcLimits limits;

    public DefaultRpcCodec(ByteBufAllocator allocator, RpcLimits limits) {
        this.allocator = Objects.requireNonNull(allocator);
        this.limits = Objects.requireNonNull(limits);
    }

    public DefaultRpcCodec() {
        this(PooledByteBufAllocator.DEFAULT, RpcLimits.DEFAULT);
    }

    @Override
    public ByteBuf encode(RpcMessage message) {
        int errorArgsBytes = RpcChecks.message(Objects.requireNonNull(message, "message"), limits);
        if (message instanceof RpcRouteMessage route)
            return route(route.sourceNodeId(), route.targetNodeId(), route.inner());
        if (message instanceof RpcHandshake h) {
            byte type =
                    switch (h.kind()) {
                        case HELLO -> 4;
                        case ACK -> 5;
                        case REJECT_DIRECTION -> 6;
                    };
            return handshake(
                    type, h.sourceNodeId(), h.targetNodeId(), h.slotIndex(), h.connectionCount());
        }
        if (message instanceof RpcHeartbeat heartbeat)
            return allocate(5)
                    .writeByte(heartbeat.kind() == RpcHeartbeat.Kind.PING ? 7 : 8)
                    .writeInt(heartbeat.sequence());
        if (message instanceof RpcRequest request) return request(request, request.body());
        if (message instanceof RpcResponse response) return response(response, errorArgsBytes);
        throw new IllegalArgumentException(
                "Unsupported RPC message: " + message.getClass().getName());
    }

    private ByteBuf allocate(long size) {
        if (size <= 0 || size > limits.maxMessageBytes())
            throw new RpcProtocolException("Frame limit");
        return allocator.buffer((int) size, (int) size);
    }

    private ByteBuf request(RpcRequest request, ByteBuf body) {
        int command = request.command(), id = request.requestId();
        long routeKey = request.routeKey(), businessId = request.businessId();
        byte businessType = request.businessIdType();
        RpcMetadata metadata = request.metadata();
        int n = body.readableBytes();
        ByteBuf out = allocate(30L + metadata.encodedLength() + n);
        try {
            out.writeByte(1);
            out.writeInt(command);
            out.writeInt(id);
            out.writeLong(routeKey);
            out.writeLong(businessId);
            out.writeByte(businessType);
            metadata(out, metadata);
            out.writeBytes(body, body.readerIndex(), n);
            return out;
        } catch (RuntimeException | Error e) {
            out.release();
            throw e;
        }
    }

    private ByteBuf response(RpcResponse response, int errorArgsBytes) {
        int id = response.requestId(), error = response.errorCode();
        var metadata = response.metadata();
        var body = response.body();
        int size = error == 0 ? body.readableBytes() : errorArgsBytes;
        ByteBuf out = allocate(13L + metadata.encodedLength() + size);
        try {
            out.writeByte(2);
            out.writeInt(id);
            out.writeInt(error);
            metadata(out, metadata);
            if (error == 0) out.writeBytes(body, body.readerIndex(), body.readableBytes());
            else response.writeErrorArgs(out);
            return out;
        } catch (RuntimeException | Error e) {
            out.release();
            throw e;
        }
    }

    private void metadata(ByteBuf out, RpcMetadata value) {
        out.writeInt(value.encodedLength());
        RpcMetadataUtil.writeTo(value, out);
    }

    private ByteBuf route(int source, int target, ByteBuf inner) {
        int size = inner.readableBytes();
        if (size == 0 || 9L + size > limits.maxMessageBytes())
            throw new RpcProtocolException("Route frame limit");
        // Only the nine-byte envelope is new. The result owns a retained inner reference.
        var out = allocator.compositeBuffer(2);
        try {
            var header = allocate(9);
            header.writeByte(3).writeInt(source).writeInt(target);
            out.addComponent(true, header);
            out.addComponent(true, inner.retainedSlice(inner.readerIndex(), size));
            return out;
        } catch (RuntimeException | Error e) {
            out.release();
            throw e;
        }
    }

    private ByteBuf handshake(byte type, int source, int target, int slot, int count) {
        validateHandshake(type, slot, count);
        ByteBuf out = allocate(17);
        out.writeByte(type);
        out.writeInt(source);
        out.writeInt(target);
        out.writeInt(slot);
        out.writeInt(count);
        return out;
    }

    private void validateHandshake(byte type, int slot, int count) {
        if ((type != 4 && type != 5 && type != 6) || count <= 0 || slot < 0 || slot >= count)
            throw new RpcProtocolException("Invalid handshake");
    }

    @Override
    public RpcMessage decode(ByteBuf in) {
        int n = in.readableBytes();
        if (n <= 0 || n > limits.maxMessageBytes()) throw new RpcProtocolException("Frame size");
        byte type = in.getByte(in.readerIndex());
        if (type == 7 || type == 8) {
            if (n != 5) throw new RpcProtocolException("Heartbeat size");
            return new RpcHeartbeat(
                    type == 7 ? RpcHeartbeat.Kind.PING : RpcHeartbeat.Kind.PONG,
                    in.getInt(in.readerIndex() + 1));
        }
        if (type == 4 || type == 5 || type == 6) {
            if (n != 17) throw new RpcProtocolException("Handshake size");
            int slot = in.getInt(in.readerIndex() + 9), count = in.getInt(in.readerIndex() + 13);
            validateHandshake(type, slot, count);
            return new RpcHandshake(
                    switch (type) {
                        case 4 -> RpcHandshake.Kind.HELLO;
                        case 5 -> RpcHandshake.Kind.ACK;
                        default -> RpcHandshake.Kind.REJECT_DIRECTION;
                    },
                    in.getInt(in.readerIndex() + 1),
                    in.getInt(in.readerIndex() + 5),
                    slot,
                    count);
        }
        if (type == 3) {
            if (n < 10) throw new RpcProtocolException("Truncated route");
            ByteBuf inner = in.slice(in.readerIndex() + 9, n - 9).asReadOnly();
            return new RpcRouteMessage(
                    in.getInt(in.readerIndex() + 1), in.getInt(in.readerIndex() + 5), inner);
        }
        int fixed = type == 1 ? 30 : type == 2 ? 13 : 0;
        if (fixed == 0 || n < fixed) throw new RpcProtocolException("Unknown/truncated frame");
        int id = in.getInt(in.readerIndex() + (type == 1 ? 5 : 1));
        if (id < 0 || (type == 2 && id == 0)) throw new RpcProtocolException("Invalid requestId");
        try {
            int count = in.getInt(in.readerIndex() + (fixed - 4));
            if (count < 0 || count > limits.maxMetadataBytes() || count > n - fixed)
                throw new RpcProtocolException("Metadata length");
            // Metadata owns an independent heap ByteBuf beyond the network callback.
            var values =
                    count == 0
                            ? RpcMetadata.EMPTY
                            : RpcMetadata.copyOf(
                                    in.slice(in.readerIndex() + fixed, count),
                                    limits.maxMetadataBytes());
            int bodySize = n - fixed - count;
            if (bodySize > limits.maxBodyBytes()) throw new RpcProtocolException("Body limit");
            ByteBuf body = in.slice(in.readerIndex() + (fixed + count), bodySize).asReadOnly();
            if (type == 2) {
                int error = in.getInt(in.readerIndex() + 5);
                if (error < 0) throw new RpcProtocolException("Invalid error response");
                return error == 0
                        ? new RpcResponse(id, 0, values, body)
                        : RpcResponse.error(id, error, values, RpcErrorArgs.read(body));
            }
            int command = in.getInt(in.readerIndex() + 1);
            long businessId = in.getLong(in.readerIndex() + 17);
            byte businessType = in.getByte(in.readerIndex() + 25);
            if (command == 0 || businessType < 0 || (businessType == 0 && businessId != 0))
                throw new RpcProtocolException("Invalid request identity");
            return new RpcRequest(
                    command,
                    id,
                    in.getLong(in.readerIndex() + 9),
                    businessId,
                    businessType,
                    values,
                    body);
        } catch (RpcProtocolException failure) {
            throw new RpcProtocolException(
                    failure.getMessage(),
                    failure,
                    id,
                    type == 2,
                    type == 1 ? in.getLong(in.readerIndex() + 9) : 0);
        }
    }
}
