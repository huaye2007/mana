package com.github.huaye2007.mana.dev.bus.role;

import com.github.huaye2007.mana.dev.message.HeartbeatReq;
import com.github.huaye2007.mana.dev.message.HeartbeatRes;
import com.github.huaye2007.mana.dev.server.GameRouterManager;
import com.github.huaye2007.mana.dev.protocol.HeartbeatConstant;
import com.github.huaye2007.mana.runtime.annotation.GameController;
import com.github.huaye2007.mana.runtime.annotation.GameMethod;
import com.github.huaye2007.mana.runtime.executor.ExecutorGroups;
import org.springframework.beans.factory.annotation.Autowired;

@GameController(group = ExecutorGroups.PLAYER)
public class RoleController {
    @Autowired
    private GameRouterManager gameRouterManager;


    @GameMethod(value = HeartbeatConstant.COMMAND)
    public void heartbeat(Long roleId, HeartbeatReq heartbeatReq){
        HeartbeatRes heartbeatRes = new HeartbeatRes();
        heartbeatRes.setTime(System.currentTimeMillis());
        gameRouterManager.reply(heartbeatRes);
    }
}
