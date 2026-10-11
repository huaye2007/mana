package cn.managame.rpc.netty;

import cn.managame.core.Metadata;
import cn.managame.rpc.error.RpcEncodeException;
import cn.managame.rpc.message.*;
import io.netty.buffer.*;
import io.netty.util.ReferenceCountUtil;

final class RpcEncoder {
    /** Bodies up to this size are copied into the frame; larger ones are referenced, not copied. */
    static final int COPY_LIMIT = 2048;
    private static final byte[] NO_METADATA = new byte[0];
    static ByteBuf encode(RpcRequest request, int requestId, int limit) {
        return encode(request, null, requestId, request.metadata(), request.body(), limit);
    }
    static ByteBuf encode(RpcResponse response, int limit) {
        return encode(null, response, response.requestId(), response.metadata(), response.body(), limit);
    }
    private static ByteBuf encode(RpcRequest request, RpcResponse response, int id,
                                  Metadata metadata, ByteBuf body, int limit) {
        ByteBuf out = null;
        try {
            byte[] bytes = metadata.isEmpty() ? NO_METADATA : metadata.bytes();
            int bodyLength = body == null ? 0 : body.readableBytes();
            long total = 4L + (request != null ? RpcProtocol.REQUEST_FIXED : RpcProtocol.RESPONSE_FIXED)
                    + bytes.length + bodyLength;
            if (total > limit || total > Integer.MAX_VALUE)
                throw new RpcEncodeException("RPC frame exceeds maxFrameSize: " + total);
            // Large bodies are not copied: the frame becomes header + body, and the body reference moves into it.
            boolean share = bodyLength > COPY_LIMIT;
            int header = (int) total - (share ? bodyLength : 0);
            out = ByteBufAllocator.DEFAULT.buffer(header, header);
            out.writeInt((int) total - 4);
            if (request != null) {
                out.writeByte(RpcWire.REQUEST).writeInt(request.command()).writeInt(id)
                        .writeLong(request.routeKey()).writeByte(request.businessIdType())
                        .writeLong(request.businessId());
            } else {
                out.writeByte(RpcWire.RESPONSE).writeInt(id).writeInt(response.errorCode());
            }
            out.writeShort(bytes.length).writeBytes(bytes);
            ByteBuf result;
            if (share) { result = Unpooled.wrappedBuffer(out, body); body = null; }
            else {
                if (body != null) out.writeBytes(body, body.readerIndex(), bodyLength);
                result = out;
            }
            out = null;
            return result;
        } catch (RuntimeException error) {
            if (error instanceof RpcEncodeException e) throw e;
            throw new RpcEncodeException("Cannot encode RPC frame", error);
        } finally {
            ReferenceCountUtil.release(out);
            ReferenceCountUtil.release(body);
        }
    }
}

