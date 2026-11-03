package cn.managame.example.server;

import cn.managame.Metadata;
import cn.managame.network.connection.Connection;
import cn.managame.runtime.Context;

/**
 * 一个客户端连接的会话，也是它发来的消息的来源上下文：登录前 roleId 为 0，登录后是角色 id，
 * Dispatcher 按它找到消息代表哪个角色执行。不可变，登录时由 GameGateway 换成新的。
 */
public record Session(Connection connection, long roleId) implements Context {
    /** businessType：角色。 */
    public static final byte ROLE = 1;

    @Override public int domain() { return 0; }
    @Override public long routeKey() { return 0; }
    @Override public byte businessType() { return roleId == 0 ? 0 : ROLE; }
    @Override public long businessId() { return roleId; }
    @Override public Metadata metadata() { return Metadata.empty(); }
}
