package com.github.huaye2007.mana.dev.client;

import com.github.huaye2007.mana.dev.protocol.GamePacketConstant;
import com.github.huaye2007.mana.serialization.ISerializer;
import com.github.huaye2007.mana.serialization.SerializerManager;

import java.util.HexFormat;

/**
 * 客户端收到的一帧服务端回包（原始形态）。
 *
 * <p>外网帧结构与 {@code GamePacketDecoder} 完全一致：
 * {@code bodyLength(int) | command(int) | seq(int) | code(int) | flags(byte) | body(bytes)}。
 * 与服务端解码器不同的是，客户端拿不到 {@code CommandRegistry} 里“回包 command → 业务类型”的映射，
 * 因此这里只保留 body 的原始字节，由调用方在已知类型时通过 {@link #decodeBody(Class)} 按需反序列化。</p>
 */
public final class ClientResponse {

    private static final ISerializer SERIALIZER =
            SerializerManager.getInstance().getISerializer(GamePacketConstant.BODY_SERIAL_TYPE);

    private final int command;
    private final int seq;
    private final int code;
    private final byte flags;
    private final byte[] body;

    public ClientResponse(int command, int seq, int code, byte flags, byte[] body) {
        this.command = command;
        this.seq = seq;
        this.code = code;
        this.flags = flags;
        this.body = body;
    }

    public int getCommand() {
        return command;
    }

    public int getSeq() {
        return seq;
    }

    public int getCode() {
        return code;
    }

    public byte getFlags() {
        return flags;
    }

    /** body 原始字节，可能为长度 0 的数组（服务端无 body 时）。 */
    public byte[] getBody() {
        return body;
    }

    /**
     * 在已知回包业务类型时，按服务端固定的 Fury 序列化方式反序列化 body。
     * body 为空时返回 {@code null}。
     */
    public <T> T decodeBody(Class<T> type) {
        return body == null || body.length == 0 ? null : SERIALIZER.deserialize(body, type);
    }

    @Override
    public String toString() {
        int bodyLength = body == null ? 0 : body.length;
        String preview = bodyLength == 0
                ? ""
                : ", bodyHex=" + HexFormat.of().formatHex(body, 0, Math.min(bodyLength, 32))
                        + (bodyLength > 32 ? "..." : "");
        return "ClientResponse{command=" + command + ", seq=" + seq + ", code=" + code
                + ", flags=" + flags + ", bodyLength=" + bodyLength + preview + '}';
    }
}
