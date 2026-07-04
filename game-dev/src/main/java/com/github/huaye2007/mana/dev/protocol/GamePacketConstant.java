package com.github.huaye2007.mana.dev.protocol;

import com.github.huaye2007.mana.serialization.SerializationType;

public final class GamePacketConstant {

    /** 帧头长度：bodyLength(4) + command(4) + seq(4) + code(4) + flags(1)。 */
    public static final int HEAD_LENGTH = 17;

    /** body 固定序列化方式：Fory，二进制、直接支持 POJO（无需 .proto 生成类）。 */
    public static final byte BODY_SERIAL_TYPE = SerializationType.FORY.typeId();

    /** body 长度上限，挡住异常/恶意 bodyLength 造成的负数长度或 OOM（完整 DoS 限流另做）。 */
    public static final int MAX_BODY_LENGTH = 1 << 20;

    /** 出站空 body：错误回包、踢下线推送这类只带帧头的包共用，避免每帧分配。 */
    public static final byte[] EMPTY_BODY = new byte[0];

    private GamePacketConstant() {
    }
}
