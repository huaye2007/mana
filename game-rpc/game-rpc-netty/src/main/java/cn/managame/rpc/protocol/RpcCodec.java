package cn.managame.rpc.protocol;

import cn.managame.common.context.Metadata;
import cn.managame.rpc.RpcRequest;
import cn.managame.rpc.RpcResponse;
import cn.managame.serialization.ISerializer;
import cn.managame.serialization.SerializerManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RPC 编解码，客户端与服务端共用，{@link #configure} 装配 解码 + 编码（共两个 handler）。
 * 直接编解码 API 对象：出站接受 {@link RpcRequest}（REQUEST/ONEWAY）与 {@link RpcResponse}（RESPONSE），
 * 入站还原为同样的对象（body 保持 byte[]，由接收方按 serialType 还原类型），没有中间帧对象。
 *
 * wire format（frame = length(4) + payload，length 不含自身，version = {@link #VERSION}）：
 * <pre>
 * payload:
 *   version(1) type(1) serialType(1) flags(1)            // flags 保留为协议演进，恒 0
 *   type==REQUEST : requestId(8) command(4) routeKey(8) busType(1) busId(8)
 *   type==ONEWAY  : command(4) routeKey(8) busType(1) busId(8)        // 无 requestId
 *   type==RESPONSE: requestId(8) code(4)
 *   metadataCount(1)
 *   metadata[i]: key(2) type(1) { type==0: strLen(2)+utf8 ; type==1: lval(8) }
 *   body: 帧内剩余字节
 * </pre>
 *
 * 拆包由 Decoder 自己做（手写长度判断，不挂 LengthFieldBasedFrameDecoder），半包等待、
 * 粘包循环消费，比通用拆包器少一个 handler 和一次帧 slice。
 * 解码不反序列化 body；编码序列化直写 ByteBuf，编码后超过 maxFrameLength 直接抛
 * {@link EncoderException} fail 该次调用，不把超长帧发给对端害死连接。
 */
public final class RpcCodec {

    public static final byte VERSION = 1;
    public static final int DEFAULT_MAX_FRAME_LENGTH = 4 * 1024 * 1024;

    private static final int LENGTH_FIELD_BYTES = 4;
    private static final byte TYPE_ONEWAY = 0;
    private static final byte TYPE_REQUEST = 1;
    private static final byte TYPE_RESPONSE = 2;

    private final Encoder encoder; // @Sharable，所有连接复用
    private final SerializerManager serializerManager;
    private final int maxFrameLength;

    public RpcCodec(SerializerManager serializerManager) {
        this(serializerManager, DEFAULT_MAX_FRAME_LENGTH);
    }

    public RpcCodec(SerializerManager serializerManager, int maxFrameLength) {
        this.encoder = new Encoder(serializerManager, maxFrameLength);
        this.serializerManager = serializerManager;
        this.maxFrameLength = maxFrameLength;
    }

    public void configure(ChannelPipeline pipeline) {
        pipeline.addLast("rpcDecoder", new Decoder(maxFrameLength));
        pipeline.addLast("rpcEncoder", encoder);
    }

    /**
     * 把请求/响应编码成一帧（length+payload）到新分配的 ByteBuf；调用方负责 release。
     * 用于广播：单次编码后对多个连接写 retainedDuplicate，避免逐目标重复序列化。
     */
    public ByteBuf encode(Object msg, ByteBufAllocator allocator) {
        ByteBuf out = allocator.buffer();
        try {
            writeFrame(out, msg, serializerManager, maxFrameLength);
            return out;
        } catch (Throwable t) {
            out.release();
            throw t;
        }
    }

    /** 写一帧：length 占位 + payload，回填长度，超长抛 {@link EncoderException}。 */
    static void writeFrame(ByteBuf out, Object msg, SerializerManager serializerManager, int maxFrameLength) {
        int lengthIndex = out.writerIndex();
        out.writeInt(0); // length 占位

        int command = 0;
        if (msg instanceof RpcRequest request) {
            command = request.getCommand();
            out.writeByte(VERSION);
            out.writeByte(request.isOneway() ? TYPE_ONEWAY : TYPE_REQUEST);
            out.writeByte(request.getSerialType());
            out.writeByte(0); // flags
            if (!request.isOneway()) {
                out.writeLong(request.getRequestId());
            }
            out.writeInt(request.getCommand());
            out.writeLong(request.getRouteKey());
            out.writeByte(request.getBusType());
            out.writeLong(request.getBusId());
            writeMetadata(out, request.getMetadata());
            writeBody(out, serializerManager, request.getSerialType(), request.getBody());
        } else {
            RpcResponse response = (RpcResponse) msg;
            out.writeByte(VERSION);
            out.writeByte(TYPE_RESPONSE);
            out.writeByte(response.serialType());
            out.writeByte(0); // flags
            out.writeLong(response.requestId());
            out.writeInt(response.code());
            writeMetadata(out, response.metadata());
            writeBody(out, serializerManager, response.serialType(), response.body());
        }

        int length = out.writerIndex() - lengthIndex - LENGTH_FIELD_BYTES;
        if (length > maxFrameLength) {
            throw new EncoderException("frame too long: " + length + " > " + maxFrameLength
                    + ", command=" + command);
        }
        out.setInt(lengthIndex, length);
    }

    private static void writeMetadata(ByteBuf out, Metadata[] metadata) {
        int count = metadata == null ? 0 : metadata.length;
        if (count > Byte.MAX_VALUE) {
            throw new EncoderException("metadata count must be <= " + Byte.MAX_VALUE);
        }
        out.writeByte(count);
        for (int i = 0; i < count; i++) {
            Metadata item = metadata[i];
            out.writeShort(item.getKey());
            out.writeByte(item.getType());
            if (item.getType() == Metadata.TYPE_STRING) {
                String value = item.getStrVal();
                byte[] strBytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                if (strBytes.length > Short.MAX_VALUE) {
                    throw new EncoderException("metadata string too long: " + strBytes.length);
                }
                out.writeShort(strBytes.length);
                out.writeBytes(strBytes);
            } else {
                out.writeLong(item.getVal());
            }
        }
    }

    private static void writeBody(ByteBuf out, SerializerManager serializerManager, byte serialType, Object body) {
        if (body == null) {
            return;
        }
        if (body instanceof byte[] bytes) {
            out.writeBytes(bytes);
            return;
        }
        ISerializer serializer = serializerManager.getISerializer(serialType);
        if (serializer == null) {
            throw new EncoderException("no serializer registered for serialType=" + serialType);
        }
        serializer.serialize(body, new ByteBufOutputStream(out)); // 直写 ByteBuf，省中间 byte[] 拷贝
    }

    @ChannelHandler.Sharable
    public static final class Encoder extends MessageToByteEncoder<Object> {

        private final SerializerManager serializerManager;
        private final int maxFrameLength;

        public Encoder(SerializerManager serializerManager, int maxFrameLength) {
            this.serializerManager = serializerManager;
            this.maxFrameLength = maxFrameLength;
        }

        @Override
        public boolean acceptOutboundMessage(Object msg) {
            return msg instanceof RpcRequest || msg instanceof RpcResponse;
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) {
            writeFrame(out, msg, serializerManager, maxFrameLength);
        }
    }

    /**
     * 拆包 + 解帧一体：长度不足等半包，长度非法断连接，帧完整则原地解码（无中间 slice）。
     */
    public static final class Decoder extends ByteToMessageDecoder {

        private final int maxFrameLength;

        public Decoder(int maxFrameLength) {
            this.maxFrameLength = maxFrameLength;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (in.readableBytes() < LENGTH_FIELD_BYTES) {
                return; // 长度字段还没到齐
            }
            int length = in.getInt(in.readerIndex());
            if (length <= 0 || length > maxFrameLength) {
                throw new DecoderException("invalid frame length: " + length + ", max=" + maxFrameLength);
            }
            if (in.readableBytes() < LENGTH_FIELD_BYTES + length) {
                return; // 半包，等数据；粘包由 ByteToMessageDecoder 的外层循环逐帧回调
            }
            in.skipBytes(LENGTH_FIELD_BYTES);
            out.add(readFrame(in, in.readerIndex() + length));
        }

        private static Object readFrame(ByteBuf in, int frameEnd) {
            byte version = in.readByte();
            if (version != VERSION) {
                // 未知版本按 v1 布局解析必然错位，立即断连而不是静默错解
                throw new DecoderException("unsupported rpc frame version: " + version
                        + ", expected " + VERSION);
            }
            byte type = in.readByte();
            byte serialType = in.readByte();
            in.readByte(); // flags，保留字段，当前忽略

            switch (type) {
                case TYPE_REQUEST -> {
                    long requestId = in.readLong();
                    return readRequest(in, frameEnd, false, requestId, serialType);
                }
                case TYPE_ONEWAY -> {
                    return readRequest(in, frameEnd, true, 0L, serialType);
                }
                case TYPE_RESPONSE -> {
                    long requestId = in.readLong();
                    int code = in.readInt();
                    Metadata[] metadata = readMetadata(in);
                    return new RpcResponse(requestId, code, serialType, readBody(in, frameEnd), metadata);
                }
                default -> throw new DecoderException("unknown rpc frame type: " + type);
            }
        }

        private static RpcRequest readRequest(ByteBuf in, int frameEnd, boolean oneway,
                                              long requestId, byte serialType) {
            int command = in.readInt();
            RpcRequest request = (oneway ? RpcRequest.oneway(command) : RpcRequest.of(command))
                    .requestId(requestId)
                    .routeKey(in.readLong())
                    .busType(in.readByte())
                    .busId(in.readLong())
                    .serialType(serialType);
            request.metadata(readMetadata(in));
            request.body(readBody(in, frameEnd));
            return request;
        }

        private static byte[] readBody(ByteBuf in, int frameEnd) {
            int bodyLength = frameEnd - in.readerIndex();
            if (bodyLength < 0) {
                throw new DecoderException("corrupted frame: fields exceed frame length");
            }
            byte[] body = new byte[bodyLength];
            in.readBytes(body);
            return body;
        }

        private static Metadata[] readMetadata(ByteBuf in) {
            int count = in.readByte();
            if (count <= 0) {
                return null;
            }
            Metadata[] metadata = new Metadata[count];
            for (int i = 0; i < count; i++) {
                short key = in.readShort();
                byte metaType = in.readByte();
                if (metaType == Metadata.TYPE_STRING) {
                    int length = in.readShort();
                    if (length < 0 || length > in.readableBytes()) {
                        throw new DecoderException("invalid metadata string length: " + length);
                    }
                    metadata[i] = Metadata.ofString(key, in.readCharSequence(length, StandardCharsets.UTF_8).toString());
                } else if (metaType == Metadata.TYPE_LONG) {
                    metadata[i] = Metadata.ofLong(key, in.readLong());
                } else {
                    throw new DecoderException("unknown metadata type: " + metaType);
                }
            }
            return metadata;
        }
    }
}
