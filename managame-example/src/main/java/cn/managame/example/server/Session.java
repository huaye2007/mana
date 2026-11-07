package cn.managame.example.server;

import cn.managame.network.connection.Connection;

/**
 * 一个客户端连接的会话：登录前 roleId 为 0，登录后是角色 id。它是业务自己的东西，不是 Context，GameGateway 为每条消息
 * 按它建立上下文。不可变，登录时由 GameGateway 换成新的。
 */
public record Session(Connection connection, long roleId) {
    /** businessType：角色。 */
    public static final byte ROLE = 1;
}
