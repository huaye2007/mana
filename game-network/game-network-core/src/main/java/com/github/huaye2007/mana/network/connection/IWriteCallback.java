package com.github.huaye2007.mana.network.connection;

public interface IWriteCallback {
    void onSuccess();
    void onFailure(Throwable cause);
}
