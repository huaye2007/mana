package cn.managame.rpc.connection;

/** Netty 写完成回调，仅供 RPC 传输实现使用。 */
public interface RpcWriteCallback {

    void onSuccess();

    void onFailure(Throwable cause);
}
