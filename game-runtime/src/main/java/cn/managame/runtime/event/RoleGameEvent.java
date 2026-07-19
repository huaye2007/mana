package cn.managame.runtime.event;

public class RoleGameEvent implements IGameEvent{

    private final long roleId;

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
