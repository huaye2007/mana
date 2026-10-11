package cn.managame.rpc.netty;

final class RpcProtocol {
    static final int MAGIC = 0x474e5352, VERSION = 2;
    static final int NONCE = 16, MAC = 32, HANDSHAKE_PAYLOAD = 12 + 8 + NONCE + NONCE + MAC;
    static final int REQUEST_FIXED = 28, RESPONSE_FIXED = 11;
    private RpcProtocol() {}
}

