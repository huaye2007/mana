package cn.managame.rpc.netty;

final class RpcProtocol {
    static final int MAGIC = 0x474e5352, VERSION = 1;
    static final int REQUEST_FIXED = 28, RESPONSE_FIXED = 11;
    private RpcProtocol() {}
}

