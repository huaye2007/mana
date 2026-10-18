package cn.managame.demo.bus.user;

import cn.managame.demo.common.runtime.GameDomain;
import cn.managame.runtime.context.ClientHandlerContext;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;

@Handler(domain = GameDomain.ROLE_ID)
public class UserHandler {

    /** Returns the request's registered response, which Runtime encodes and writes on its connection. */
    @HandlerMethod(domain = GameDomain.LOGIN_ID)
    public LoginRes login(ClientHandlerContext context,LoginReq loginReq){
        // Implement token verification here. Only after successful verification, bind the
        // business-selected routeKey and authenticated roleId through context.connection():
        // context.connection().set(GameSession.KEY,
        //         new GameSession(selectedRouteKey, authenticatedRoleId));
        // Until verification is implemented, this skeleton installs no session and answers with roleId 0.
        LoginRes response = new LoginRes();
        response.setRoleId(0L);
        return response;
    }
}
