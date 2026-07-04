package cn.managame.dev.server;

import cn.managame.dev.protocol.GameErrorCode;
import cn.managame.dev.protocol.GamePacket;
import cn.managame.dev.protocol.GamePacketConstant;
import cn.managame.dev.protocol.KickConstant;
import cn.managame.network.connection.IWriteCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * roleId → 在线 session 绑定表。登陆成功后 {@link #bind}，断线时
 * {@code GameHandler#onDisconnect} 调 {@link #unbind}。
 */
@Service
public class PlayerSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(PlayerSessionManager.class);

    private final Map<Long, PlayerSession> playerSessionMap = new ConcurrentHashMap<>();

    /** 绑定 roleId → session；同 roleId 已在线（顶号）时旧连接先收踢下线推送再被关闭。 */
    public void bind(PlayerSession session) {
        PlayerSession old = playerSessionMap.put(session.getRoleId(), session);
        if (old != null && old != session) {
            kick(old, GameErrorCode.DUPLICATE_LOGIN);
        }
    }

    /**
     * 踢下线：先推一帧踢下线原因（command={@link KickConstant#COMMAND}，code=reason），
     * 写完成（成功或失败）后再关连接，避免 close 抢在 flush 之前把帧吞掉。
     */
    public void kick(PlayerSession session, int reason) {
        logger.info("kick roleId={}, reason={}", session.getRoleId(), reason);
        GamePacket packet = GamePacket.of(KickConstant.COMMAND, 0, reason, GamePacketConstant.EMPTY_BODY);
        session.writeMsg(packet, new IWriteCallback() {
            @Override
            public void onSuccess() {
                session.close();
            }

            @Override
            public void onFailure(Throwable cause) {
                session.close();
            }
        });
    }

    public PlayerSession get(long roleId) {
        return playerSessionMap.get(roleId);
    }

    public void unbind(PlayerSession session) {
        if (session.getRoleId() != 0) {
            playerSessionMap.remove(session.getRoleId(), session);  // 条件移除:仅当 map 中仍是该 session 才删,避免顶号重连后误删新 session
        }
    }

    /** 当前在线（已完成登陆绑定）人数。 */
    public int onlineCount() {
        return playerSessionMap.size();
    }

    /** 遍历所有在线 session（弱一致快照），广播等全服操作用。 */
    public void forEachOnline(Consumer<PlayerSession> action) {
        playerSessionMap.values().forEach(action);
    }
}
