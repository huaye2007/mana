package com.github.huaye2007.mana.runtime.event;

public class RoleGameEvent implements IGameEvent{

    private long roleId;

    public RoleGameEvent(long roleId){
        this.roleId = roleId;
    }

    @Override
    public long routerKey() {
        return roleId;
    }

    public long getRoleId() {
        return roleId;
    }
}
