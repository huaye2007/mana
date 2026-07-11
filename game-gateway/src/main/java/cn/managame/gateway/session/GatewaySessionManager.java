package cn.managame.gateway.session;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketConstant;
import cn.managame.network.connection.IWriteCallback;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class GatewaySessionManager {
    public static final int KICK_COMMAND = 1;

    private final ConcurrentHashMap<Long, GatewaySession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, GatewaySession> roles = new ConcurrentHashMap<>();

    public void add(GatewaySession session) {
        Objects.requireNonNull(session, "session");
        GatewaySession previous = sessions.putIfAbsent(session.getSessionId(), session);
        if (previous != null && previous != session) throw new IllegalStateException("duplicate sessionId: " + session.getSessionId());
    }

    public synchronized void remove(GatewaySession session) {
        if (session == null) return;
        sessions.remove(session.getSessionId(), session);
        long roleId = session.getRoleId();
        if (roleId > 0) roles.remove(roleId, session);
    }

    public GatewaySession getBySessionId(long sessionId) { return sessions.get(sessionId); }
    public GatewaySession getByRoleId(long roleId) { return roles.get(roleId); }

    /** Atomically binds a role and kicks the previous live session, if any. */
    public synchronized void bindRole(GatewaySession session, long roleId) {
        Objects.requireNonNull(session, "session");
        if (roleId <= 0) throw new IllegalArgumentException("roleId must be positive");
        if (sessions.get(session.getSessionId()) != session || !session.getConnection().isActive()) return;
        long oldRole = session.getRoleId();
        if (oldRole > 0 && oldRole != roleId) roles.remove(oldRole, session);
        session.setRoleId(roleId);
        GatewaySession previous = roles.put(roleId, session);
        if (previous != null && previous != session) kick(previous, GatewayErrorCode.DUPLICATE_LOGIN);
    }

    public void kick(GatewaySession session, int reason) {
        if (session == null) return;
        try {
            if (session.getConnection().isActive()) {
                session.writeMsg(GatewayPacket.of(KICK_COMMAND, 0, reason, GatewayPacketConstant.EMPTY_BODY),
                        new IWriteCallback() {
                            @Override public void onSuccess() { session.close(); }
                            @Override public void onFailure(Throwable cause) { session.close(); }
                        });
            } else {
                session.close();
            }
        } catch (RuntimeException ignored) {
            session.close();
        }
    }

    public int connectionCount() { return sessions.size(); }
    public int boundRoleCount() { return roles.size(); }
    public void forEach(Consumer<GatewaySession> action) { sessions.values().forEach(action); }
    public void closeAll() { sessions.values().forEach(GatewaySession::close); }
}
