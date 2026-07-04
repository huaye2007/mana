package cn.managame.dev.server;

import cn.managame.dev.protocol.GamePacket;
import cn.managame.network.connection.IConnection;
import cn.managame.network.handler.INetworkHandler;
import cn.managame.network.session.ISession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameHandler implements INetworkHandler {

    private static final Logger logger = LoggerFactory.getLogger(GameHandler.class);

    private final PlayerSessionManager playerSessionManager;
    private final GameRouterManager gameRouterManager;

    public GameHandler(PlayerSessionManager playerSessionManager, GameRouterManager gameRouterManager) {
        this.playerSessionManager = playerSessionManager;
        this.gameRouterManager = gameRouterManager;
    }

    @Override
    public void onConnect(ISession session) {
        // 连接建立，等待客户端首包；玩家绑定在登陆成功后由业务侧完成
    }

    @Override
    public void onMessage(ISession session, Object packet) {
        gameRouterManager.handleGameMsg((PlayerSession) session, (GamePacket) packet);
    }

    @Override
    public void onDisconnect(ISession session) {
        playerSessionManager.unbind((PlayerSession) session);
    }

    @Override
    public void onException(ISession session, Throwable cause) {
        logger.warn("session exception, closing connection", cause);
        session.close();
    }

    @Override
    public ISession createSession(IConnection connection) {
        return new PlayerSession(connection);
    }
}
