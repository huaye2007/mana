package cn.managame.spring.rpc;

/** Thread-safe business codec. Arrays and decoded objects must not borrow transport buffers. */
public interface GameRpcCodec {
    byte[] encode(Object message);
    <T> T decode(byte[] body, Class<T> type);
}
