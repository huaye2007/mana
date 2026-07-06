package cn.managame.gateway.codec;

import cn.managame.serialization.SerializationType;

/**
 * 外网帧常量，与 game-dev 的 {@code GamePacketConstant} 保持一致，
 * 保证同一个客户端帧既能直连游戏服也能走网关。
 */
public final class GatewayPacketConstant {

    /** 帧头长度：bodyLength(4) + command(4) + seq(4) + code(4) + flags(1)。 */
    public static final int HEAD_LENGTH = 17;

    /** body 长度上限，挡住异常/恶意 bodyLength 造成的负数长度或 OOM。 */
    public static final int MAX_BODY_LENGTH = 1 << 20;

    /** 出站空 body：错误回包、踢下线推送共用，避免每帧分配。 */
    public static final byte[] EMPTY_BODY = new byte[0];

    /**
     * body 固定序列化方式（Fory）。网关自己不解 body，
     * 只在转发给后端的 RPC 帧上标注 serialType，由游戏服按此反序列化。
     */
    public static final byte BODY_SERIAL_TYPE = SerializationType.FORY.typeId();

    /** flags 位：body 已加密（{@link BodyCodec} 解密后再转发）。 */
    public static final byte FLAG_ENCRYPTED = 0x01;

    /** flags 位：body 已压缩（{@link BodyCodec} 解压后再转发）。 */
    public static final byte FLAG_COMPRESSED = 0x02;

    private GatewayPacketConstant() {
    }
}
