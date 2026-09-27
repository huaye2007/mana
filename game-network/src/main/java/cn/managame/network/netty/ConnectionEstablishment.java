package cn.managame.network.netty;

import cn.managame.network.connection.Connection;

/** Owner-specific admission and result delivery; no protocol knowledge. */
interface ConnectionEstablishment {
    boolean claimSuccess();
    void success(Connection connection);
    void networkFailure(Throwable cause);
    default void release() {}
}
