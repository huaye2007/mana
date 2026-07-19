package cn.managame.gateway.network;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketConstant;
import cn.managame.gateway.filter.GatewayFilter;
import cn.managame.gateway.rpc.PacketForwarder;
import cn.managame.gateway.session.GatewaySession;
import cn.managame.gateway.session.GatewaySessionManager;
import cn.managame.network.connection.IConnection;
import cn.managame.network.handler.IConnectionHandler;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public final class GatewayNetworkHandler implements IConnectionHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayNetworkHandler.class);
    private final GatewaySessionManager sessionManager;
    private final GatewayFilter[] filters;
    private final PacketForwarder forwarder;

    public GatewayNetworkHandler(GatewaySessionManager sessionManager, GatewayFilter[] filters,
                                 PacketForwarder forwarder) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.filters = Objects.requireNonNull(filters, "filters").clone();
        for (GatewayFilter filter : this.filters) Objects.requireNonNull(filter, "filter");
        this.forwarder = Objects.requireNonNull(forwarder, "forwarder");
    }

    @Override
    public void onConnect(IConnection connection) {
        GatewaySession session = sessionManager.create(connection);
        try {
            if (!accept(filters, session)) {
                session.close();
                return;
            }
        } catch (RuntimeException error) {
            session.close();
            throw error;
        }
        try {
            sessionManager.add(session);
        } catch (RuntimeException error) {
            disconnect(filters, session);
            session.close();
            throw error;
        }
    }

    @Override
    public void onMessage(IConnection connection, Object message) {
        GatewaySession session = sessionManager.getByConnection(connection);
        if (session == null) {
            ReferenceCountUtil.release(message);
            connection.close();
            return;
        }
        if (!(message instanceof GatewayPacket packet)) {
            ReferenceCountUtil.release(message);
            session.close();
            return;
        }
        if (packet.getCommand() <= 0 || packet.getCode() != 0) {
            session.writeMsg(GatewayPacket.of(packet.getCommand(), packet.getSeq(),
                    GatewayErrorCode.BAD_REQUEST, GatewayPacketConstant.EMPTY_BODY));
            return;
        }
        int code = filter(filters, session, packet);
        if (code != GatewayErrorCode.OK) {
            session.writeMsg(GatewayPacket.of(packet.getCommand(), packet.getSeq(), code, GatewayPacketConstant.EMPTY_BODY));
            return;
        }
        forwarder.forward(session, packet);
    }

    @Override
    public void onDisconnect(IConnection connection) {
        GatewaySession session = sessionManager.removeByConnection(connection);
        if (session != null) disconnect(filters, session);
    }

    @Override
    public void onException(IConnection connection, Throwable cause) {
        GatewaySession session = sessionManager.getByConnection(connection);
        if (session != null) {
            log.debug("gateway client connection failed: sessionId={}", session.getSessionId(), cause);
        } else {
            log.debug("gateway client connection failed before session creation", cause);
        }
        connection.close();
    }

    @Override public void onIdle(IConnection connection) { connection.close(); }

    static boolean accept(GatewayFilter[] filters, GatewaySession session) {
        int accepted = 0;
        try {
            for (; accepted < filters.length; accepted++) {
                if (!filters[accepted].onConnect(session)) {
                    disconnect(filters, session, accepted);
                    return false;
                }
            }
            return true;
        } catch (RuntimeException error) {
            disconnect(filters, session, accepted);
            throw error;
        }
    }

    static int filter(GatewayFilter[] filters, GatewaySession session, GatewayPacket packet) {
        for (GatewayFilter filter : filters) {
            int code = filter.onPacket(session, packet);
            if (code != GatewayErrorCode.OK) return code;
        }
        return GatewayErrorCode.OK;
    }

    static void disconnect(GatewayFilter[] filters, GatewaySession session) {
        disconnect(filters, session, filters.length);
    }

    private static void disconnect(GatewayFilter[] filters, GatewaySession session, int accepted) {
        for (int i = accepted - 1; i >= 0; i--) {
            try {
                filters[i].onDisconnect(session);
            } catch (RuntimeException ignored) { }
        }
    }
}
