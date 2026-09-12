package cn.managame.network;

/** One result per accepted attempt. Callbacks must not block the network executor. */
public interface ConnectCallback {
    void onSuccess(Connection connection);

    void onFailure(Throwable cause);
}
