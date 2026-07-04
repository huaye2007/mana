package cn.managame.dev.bus.login;

import cn.managame.dev.message.LoginReq;
import cn.managame.dev.message.LoginRes;
import cn.managame.dev.protocol.GameErrorCode;
import cn.managame.dev.server.GameBusinessException;
import cn.managame.dev.server.GameRouterManager;
import cn.managame.dev.server.PlayerSession;
import cn.managame.dev.server.PlayerSessionManager;
import cn.managame.runtime.annotation.GameController;
import cn.managame.runtime.annotation.GameMethod;
import cn.managame.runtime.event.EventBus;
import cn.managame.runtime.executor.ExecutorGroups;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

@GameController(group = ExecutorGroups.LOGIN)
public class LoginController {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);

    @Autowired
    private PlayerSessionManager playerSessionManager;
    @Autowired
    private GameRouterManager gameRouterManager;
    @Autowired
    private UserRepository userRepository;


    @GameMethod(value = 1000, routerKeyMethod = "getUserId")
    public void login(PlayerSession playerSession, LoginReq loginReq) {
        if (loginReq == null || loginReq.getUserId() <= 0) {
            // 业务拒绝走异常：GameTaskFailureReplier 统一按 code 回包，主流程不散落错误分支
            throw new GameBusinessException(GameErrorCode.BAD_REQUEST, "invalid userId");
        }
        playerSession.setRoleId(loginReq.getUserId());
        playerSessionManager.bind(playerSession);

        User user = userRepository.cacheLoad(loginReq.getUserId());
        if(user == null){
            user = new User();
            user.setUserId(loginReq.getUserId());
            userRepository.cacheInsert(user);
        }
        Long roleId = user.getRoleMap().get(loginReq.getServerId());
        if(roleId == null){
            roleId = System.currentTimeMillis();
            user.getRoleMap().put(loginReq.getServerId(), roleId);
            userRepository.cacheUpdate(user);
        }
        LoginRes loginRes = new LoginRes();
        loginRes.setRoleId(roleId);
        gameRouterManager.reply(loginRes);
        // 登陆完成事件：监听者在 PLAYER 组按 roleId 串行执行（见 PlayerLoginEventHandler）
        EventBus.getInstance().publishEvent(
                new PlayerLoginEvent(loginReq.getUserId(), roleId, loginReq.getServerId()));
        logger.info("player {} logged in", loginReq.getUserId());
    }
}
