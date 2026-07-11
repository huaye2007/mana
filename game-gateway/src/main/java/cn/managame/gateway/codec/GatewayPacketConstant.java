package cn.managame.gateway.codec;

import cn.managame.serialization.SerializationType;

public final class GatewayPacketConstant {
    /** bodyLength + command + seq + code + flags. */
    public static final int HEAD_LENGTH = Integer.BYTES * 4 + Byte.BYTES;
    public static final int MAX_BODY_LENGTH = 1 << 20;
    public static final byte[] EMPTY_BODY = new byte[0];
    public static final byte BODY_SERIAL_TYPE = SerializationType.FORY.typeId();
    public static final byte FLAG_ENCRYPTED = 0x01;
    public static final byte FLAG_COMPRESSED = 0x02;

    private GatewayPacketConstant() { }
}
