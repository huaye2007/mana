package cn.managame.gateway.session;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketConstant;
import cn.managame.network.connection.IWriteCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 网关会话表：sessionId → 会话 全量索引 + roleId → 会话 已绑定索引。
 *
 * <p>TCP 与 WebSocket 两个 server 共用一个实例，后端按 sessionId/roleId
 * 推送时不关心客户端走的哪种接入。顶号（同 roleId 二次绑定）时旧连接
 * 先收踢下线帧再关闭。</p>
 */
public class GatewaySessionManager {

    private static final Logger logger = LoggerFactory.getLogger(GatewaySessionManager.class);

    /** 踢下线推送 command，与 game-dev 的 KickConstant 对齐（1~999 为系统命令段）。 */
    public static final int KICK_COMMAND = 1;

    private final Map<Long, GatewaySession> sessionMap = new ConcurrentHashMap<>();
    private final Map<Long, GatewaySession> roleSessionMap = new ConcurrentHashMap<>();

    /** 连接建立时登记（认证前就要登记，后端响应按 sessionId 找回会话）。 */
    public void add(GatewaySession session) {
        sessionMap.put(session.getSessionId(), session);
    }

    /** 断线解绑：条件移除，避免顶号后新会话被旧连接的断线清理误删。 */
    public void remove(GatewaySession session) {
        sessionMap.remove(session.getSessionId(), session);
        if (session.getRoleId() != 0) {
            roleSessionMap.remove(session.getRoleId(), session);
        }
    }

    public GatewaySession getBySessionId(long sessionId) {
        return sessionMap.get(sessionId);
    }

    public GatewaySession getByRoleId(long roleId) {
        return roleSessionMap.get(roleId);
    }

    /** 绑定 roleId → 会话（后端首次回带 roleId 触发）；顶号时旧连接踢下线。 */
    public void bindRole(GatewaySession session, long roleId) {
        session.setRoleId(roleId);
        GatewaySession old = roleSessionMap.put(roleId, session);
        if (old != null && old != session) {
            kick(old, GatewayErrorCode.DUPLICATE_LOGIN);
        }
    }

    /**
     * 踢下线：先推一帧原因（command={@link #KICK_COMMAND}，code=reason），
     * 写完成（成功或失败）后再关连接，避免 close 抢在 flush 之前把帧吞掉。
     */
    public void kick(GatewaySession session, int reason) {
        logger.info("kick sessionId={}, roleId={}, reason={}",
                session.getSessionId(), session.getRoleId(), reason);
        GatewayPacket packet = GatewayPacket.of(KICK_COMMAND, 0, reason, GatewayPacketConstant.EMPTY_BODY);
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

    /** 当前连接总数（含未认证）。 */
    public int connectionCount() {
        return sessionMap.size();
    }

    /** 已绑定角色数。 */
    public int boundRoleCount() {
        return roleSessionMap.size();
    }

    /** 遍历全部会话（弱一致快照），广播用。 */
    public void forEach(Consumer<GatewaySession> action) {
        sessionMap.values().forEach(action);
    }
}
