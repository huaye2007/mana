package cn.managame.gateway.network;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketConstant;
import cn.managame.gateway.filter.FilterChain;
import cn.managame.gateway.rpc.PacketForwarder;
import cn.managame.gateway.session.GatewaySession;
import cn.managame.gateway.session.GatewaySessionManager;
import cn.managame.network.connection.IConnection;
import cn.managame.network.handler.INetworkHandler;
import cn.managame.network.session.ISession;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public final class GatewayNetworkHandler implements INetworkHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayNetworkHandler.class);
    private final GatewaySessionManager sessionManager;
    private final FilterChain filterChain;
    private final PacketForwarder forwarder;

    public GatewayNetworkHandler(GatewaySessionManager sessionManager, FilterChain filterChain,
                                 PacketForwarder forwarder) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.filterChain = Objects.requireNonNull(filterChain, "filterChain");
        this.forwarder = Objects.requireNonNull(forwarder, "forwarder");
    }

    @Override public ISession createSession(IConnection connection) {
        return new GatewaySession(connection, connection.getRemoteAddress());
    }

    @Override
    public void onConnect(ISession rawSession) {
        GatewaySession session = requireGatewaySession(rawSession);
        if (!filterChain.onConnect(session)) {
            session.close();
            return;
        }
        sessionManager.add(session);
    }

    @Override
    public void onMessage(ISession rawSession, Object message) {
        GatewaySession session = requireGatewaySession(rawSession);
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
        int code = filterChain.onPacket(session, packet);
        if (code != GatewayErrorCode.OK) {
            session.writeMsg(GatewayPacket.of(packet.getCommand(), packet.getSeq(), code, GatewayPacketConstant.EMPTY_BODY));
            return;
        }
        forwarder.forward(session, packet);
    }

    @Override
    public void onDisconnect(ISession rawSession) {
        GatewaySession session = requireGatewaySession(rawSession);
        sessionManager.remove(session);
        filterChain.onDisconnect(session);
    }

    @Override
    public void onException(ISession rawSession, Throwable cause) {
        GatewaySession session = requireGatewaySession(rawSession);
        log.debug("gateway client connection failed: sessionId={}", session.getSessionId(), cause);
        session.close();
    }

    @Override public boolean onIdle(ISession session) { return true; }

    private static GatewaySession requireGatewaySession(ISession session) {
        if (session instanceof GatewaySession gatewaySession) return gatewaySession;
        throw new IllegalArgumentException("expected GatewaySession, got " + session.getClass().getName());
    }
}
