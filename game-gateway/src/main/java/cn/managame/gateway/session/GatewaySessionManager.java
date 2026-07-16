package cn.managame.gateway.session;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketConstant;
import cn.managame.network.connection.IWriteCallback;
import cn.managame.network.connection.IConnection;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class GatewaySessionManager {
    public static final int KICK_COMMAND = 1;

    private final ConcurrentHashMap<Long, GatewaySession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, GatewaySession> roles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IConnection, GatewaySession> connections = new ConcurrentHashMap<>();
    private final long serverPrefix;
    private final AtomicLong sequence = new AtomicLong();

    public GatewaySessionManager() {
        this(0);
    }

    public GatewaySessionManager(int serverId) {
        if (serverId < 0) throw new IllegalArgumentException("serverId must be non-negative");
        this.serverPrefix = ((long) serverId) << 32;
    }

    public GatewaySession create(IConnection connection) {
        Objects.requireNonNull(connection, "connection");
        long value = sequence.incrementAndGet() & 0xffff_ffffL;
        if (value == 0) throw new IllegalStateException("gateway session id sequence exhausted");
        return new GatewaySession(serverPrefix | value, connection, connection.getRemoteAddress());
    }

    public void add(GatewaySession session) {
        Objects.requireNonNull(session, "session");
        GatewaySession previous = sessions.putIfAbsent(session.getSessionId(), session);
        if (previous != null && previous != session) throw new IllegalStateException("duplicate sessionId: " + session.getSessionId());
        GatewaySession previousConnection = connections.putIfAbsent(session.getConnection(), session);
        if (previousConnection != null && previousConnection != session) {
            sessions.remove(session.getSessionId(), session);
            throw new IllegalStateException("connection already has a session");
        }
    }

    public synchronized void remove(GatewaySession session) {
        if (session == null) return;
        sessions.remove(session.getSessionId(), session);
        connections.remove(session.getConnection(), session);
        long roleId = session.getRoleId();
        if (roleId > 0) roles.remove(roleId, session);
    }

    public GatewaySession getBySessionId(long sessionId) { return sessions.get(sessionId); }
    public GatewaySession getByRoleId(long roleId) { return roles.get(roleId); }
    public GatewaySession getByConnection(IConnection connection) { return connections.get(connection); }

    public GatewaySession removeByConnection(IConnection connection) {
        GatewaySession session = connections.get(connection);
        remove(session);
        return session;
    }

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
