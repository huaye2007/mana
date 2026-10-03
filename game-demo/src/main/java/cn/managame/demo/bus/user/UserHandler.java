package cn.managame.demo.bus.user;

import cn.managame.demo.common.runtime.GameDomain;
import cn.managame.runtime.context.DefaultHandlerContext;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;

@Handler(domain = GameDomain.ROLE_ID)
public class UserHandler {

    @HandlerMethod(domain = GameDomain.LOGIN_ID)
    public void login(DefaultHandlerContext context,LoginReq loginReq){
        // Implement token verification here. Only after successful verification, bind the
        // business-selected routeKey and authenticated roleId through context.connection():
        // context.connection().set(GameSession.KEY,
        //         new GameSession(selectedRouteKey, new RoleId(authenticatedRoleId)));
        // Until verification is implemented, this skeleton does not install a session.
    }
}
