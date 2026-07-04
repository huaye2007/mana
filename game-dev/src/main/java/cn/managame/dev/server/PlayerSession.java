package cn.managame.dev.server;

import cn.managame.network.connection.IConnection;
import cn.managame.network.session.DefaultSession;

public class PlayerSession extends DefaultSession {
    private long roleId;

    public PlayerSession(IConnection connection) {
        super(connection);
    }

    public long getRoleId() {
        return roleId;
    }

    public void setRoleId(long roleId) {
        this.roleId = roleId;
    }
}
