package cn.managame.rpc.protocol;

import java.time.Duration;

/** Shared protocol validation used by the codec and call admission; not a business API. */
public final class RpcChecks {
    private RpcChecks() {}

    /** Codec-independent rules; returns the validated error payload size for the encoder. */
    public static int message(RpcMessage message, RpcLimits limits) {
        int errorArgsBytes = 0;
        int id;
        boolean response;
        long routeKey;
        RpcMetadata metadata;
        io.netty.buffer.ByteBuf body;
        if (message instanceof RpcRequest q) {
            id = q.requestId();
            response = false;
            routeKey = q.routeKey();
            metadata = q.metadata();
            body = q.body();
        } else if (message instanceof RpcResponse r) {
            id = r.requestId();
            response = true;
            routeKey = 0;
            metadata = r.metadata();
            body = r.body();
        } else {
            if (message instanceof RpcRouteMessage r && !r.inner().isReadable())
                throw new RpcProtocolException("Empty route payload");
            return 0;
        }
        // An invalid ID must never be used to correlate a protocol error.
        if (id < 0 || (response && id == 0)) throw new RpcProtocolException("Invalid requestId");
        try {
            if (message instanceof RpcRequest q) {
                if (q.command() == 0
                        || q.businessIdType() < 0
                        || (q.businessIdType() == 0 && q.businessId() != 0))
                    throw new RpcProtocolException("Invalid request identity");
            } else {
                var r = (RpcResponse) message;
                if (r.errorCode() < 0
                        || (r.errorCode() != 0 && body != null && body.isReadable())
                        || (r.errorCode() == 0 && r.hasErrorArgs()))
                    throw new RpcProtocolException("Invalid error response");
                if (r.errorCode() != 0) errorArgsBytes = r.errorArgsLength(limits.maxBodyBytes());
            }
            RpcMetadataUtil.validateSize(metadata, limits.maxMetadataBytes());
            if (body != null && body.readableBytes() > limits.maxBodyBytes())
                throw new RpcProtocolException("Body limit");
            return errorArgsBytes;
        } catch (RpcProtocolException failure) {
            throw new RpcProtocolException(failure.getMessage(), failure, id, response, routeKey);
        }
    }

    public static long timeoutNanos(Duration value) {
        java.util.Objects.requireNonNull(value, "timeout");
        try {
            long n = value.toNanos();
            if (n <= 0) throw new IllegalArgumentException("Duration must be finite and positive");
            return n;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Duration overflow", e);
        }
    }

    static void business(byte type, long id) {
        if (type < 0 || (type == 0 && id != 0))
            throw new IllegalArgumentException("Invalid business identity");
    }
}
