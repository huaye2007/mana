package cn.managame.gateway.codec;

/**
 * body 字节变换钩子：按帧头 flags 位做解密/解压（入站）与加密/压缩（出站）。
 *
 * <p>网关默认不启用任何变换（{@link #IDENTITY}）。接入具体加密（如 AES/XOR 流加密）
 * 或压缩（如 zlib）时实现本接口并在装配 {@link GatewayPacketDecoder} /
 * {@link GatewayPacketEncoder} 时传入；实现必须无状态或自行保证线程安全，
 * 因为同一实例会被多个 channel 共享调用。</p>
 */
public interface BodyCodec {

    /** 恒等实现：flags 不带任何变换位时的默认行为。 */
    BodyCodec IDENTITY = new BodyCodec() {
        @Override
        public byte[] decode(byte flags, byte[] body) {
            return body;
        }

        @Override
        public byte[] encode(byte flags, byte[] body) {
            return body;
        }
    };

    /** 入站：按 flags 还原 body（先解密后解压）。返回还原后的明文字节。 */
    byte[] decode(byte flags, byte[] body);

    /** 出站：按 flags 处理 body（先压缩后加密）。返回处理后的线上字节。 */
    byte[] encode(byte flags, byte[] body);
}
