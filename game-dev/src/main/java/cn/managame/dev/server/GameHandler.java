package cn.managame.dev.server;

import cn.managame.dev.protocol.GamePacket;
import cn.managame.network.connection.IConnection;
import cn.managame.network.handler.IConnectionHandler;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameHandler implements IConnectionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GameHandler.class);

    private final PlayerSessionManager playerSessionManager;
    private final GameRouterManager gameRouterManager;

    public GameHandler(PlayerSessionManager playerSessionManager, GameRouterManager gameRouterManager) {
        this.playerSessionManager = playerSessionManager;
        this.gameRouterManager = gameRouterManager;
    }

    @Override
    public void onConnect(IConnection connection) {
        playerSessionManager.addConnection(new PlayerSession(connection));
    }

    @Override
    public void onMessage(IConnection connection, Object packet) {
        PlayerSession session = playerSessionManager.get(connection);
        if (session == null) {
            ReferenceCountUtil.release(packet);
            connection.close();
            return;
        }
        gameRouterManager.handleGameMsg(session, (GamePacket) packet);
    }

    @Override
    public void onDisconnect(IConnection connection) {
        playerSessionManager.unbind(playerSessionManager.removeConnection(connection));
    }

    @Override
    public void onException(IConnection connection, Throwable cause) {
        logger.warn("connection exception, closing connection", cause);
        connection.close();
    }

    @Override
    public void onIdle(IConnection connection) {
        connection.close();
    }
}
