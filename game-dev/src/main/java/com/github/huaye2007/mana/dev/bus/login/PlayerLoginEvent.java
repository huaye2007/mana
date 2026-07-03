package com.github.huaye2007.mana.dev.bus.login;

import com.github.huaye2007.mana.runtime.event.IGameEvent;
import com.github.huaye2007.mana.runtime.event.RoleGameEvent;

/**
 * 玩家登陆完成事件。routerKey 用 roleId：监听者跑在 PLAYER 组时与该玩家的
 * 命令任务同 worker 串行，读写玩家数据无需加锁。
 */
public class PlayerLoginEvent extends RoleGameEvent {

    private final long userId;
    private final int serverId;

    public PlayerLoginEvent(long userId, long roleId, int serverId) {
        super(roleId);
        this.userId = userId;
        this.serverId = serverId;
    }


    public long getUserId() {
        return userId;
    }

    public int getServerId() {
        return serverId;
    }
}
