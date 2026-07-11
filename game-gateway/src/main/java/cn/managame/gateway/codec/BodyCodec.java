package cn.managame.gateway.codec;

/** Optional transformation hook for compression or encryption of packet bodies. */
public interface BodyCodec {
    BodyCodec IDENTITY = new BodyCodec() {
        @Override public byte[] decode(byte flags, byte[] body) { return body; }
        @Override public byte[] encode(byte flags, byte[] body) { return body; }
    };

    byte[] decode(byte flags, byte[] body);

    byte[] encode(byte flags, byte[] body);
}
