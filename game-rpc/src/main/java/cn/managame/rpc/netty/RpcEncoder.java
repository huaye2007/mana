package cn.managame.rpc.netty;

import cn.managame.core.Metadata;
import cn.managame.core.Metadatas;
import cn.managame.rpc.error.RpcEncodeException;
import cn.managame.rpc.message.*;
import io.netty.buffer.*;
import io.netty.util.ReferenceCountUtil;

final class RpcEncoder {
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
            byte[] bytes = metadata.bytes();
            // Custom Metadata implementations must also obey Core's structural contract.
            Metadatas.wrap(bytes);
            int bodyLength = body == null ? 0 : body.readableBytes();
            long total = 4L + (request != null ? RpcProtocol.REQUEST_FIXED : RpcProtocol.RESPONSE_FIXED)
                    + bytes.length + bodyLength;
            if (total > limit || total > Integer.MAX_VALUE)
                throw new RpcEncodeException("RPC frame exceeds maxFrameSize: " + total);
            out = ByteBufAllocator.DEFAULT.buffer((int) total, (int) total);
            out.writeInt((int) total - 4);
            if (request != null) {
                out.writeByte(RpcWire.REQUEST).writeInt(request.command()).writeInt(id)
                        .writeLong(request.routeKey()).writeByte(request.businessIdType())
                        .writeLong(request.businessId());
            } else {
                out.writeByte(RpcWire.RESPONSE).writeInt(id).writeInt(response.errorCode());
            }
            out.writeShort(bytes.length).writeBytes(bytes);
            if (body != null) out.writeBytes(body, body.readerIndex(), bodyLength);
            ByteBuf result = out;
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

