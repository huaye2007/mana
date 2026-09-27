package cn.managame.network.connector;

import cn.managame.network.connection.Connection;

public interface ConnectCallback {
    void onSuccess(Connection connection);
    void onFailure(Throwable cause);
}