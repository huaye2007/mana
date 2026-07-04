package cn.managame.dev.bus.role;

import cn.managame.dev.message.HeartbeatReq;
import cn.managame.dev.message.HeartbeatRes;
import cn.managame.dev.server.GameRouterManager;
import cn.managame.dev.protocol.HeartbeatConstant;
import cn.managame.runtime.annotation.GameController;
import cn.managame.runtime.annotation.GameMethod;
import cn.managame.runtime.executor.ExecutorGroups;
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
