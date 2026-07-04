package cn.managame.network.connection;

public interface IWriteCallback {
    void onSuccess();
    void onFailure(Throwable cause);
}
