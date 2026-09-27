package cn.managame.rpc.call;

/** Application result callback. The application's RpcHandler decides when and where to invoke it. */
@FunctionalInterface
public interface RpcCallback<T> {
    void onResponse(T response);
}

