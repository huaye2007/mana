package com.github.huaye2007.mana.dev.server;

import com.github.huaye2007.mana.network.connection.IConnection;
import com.github.huaye2007.mana.network.session.DefaultSession;

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
