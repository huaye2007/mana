package cn.managame.demo.bus.role;

import cn.managame.demo.network.message.PingMessage;
import cn.managame.demo.common.runtime.GameDomain;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.context.ClientHandlerContext;

@Handler(domain = GameDomain.ROLE_ID)
public class RoleHandler {
    @HandlerMethod
    public void ping(ClientHandlerContext context, PingMessage request) {
        long roleId = context.businessId();
        System.out.printf("Role ping: roleId=%d timestamp=%d%n", roleId, request.timestamp());
    }
}
