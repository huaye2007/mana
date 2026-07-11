package cn.managame.gateway.rpc;

import cn.managame.common.context.MetadataKeys;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketConstant;
import cn.managame.gateway.session.GatewaySession;
import cn.managame.gateway.session.GatewaySessionManager;
import cn.managame.rpc.RpcMessageHandler;
import cn.managame.rpc.RpcRequest;
import cn.managame.rpc.connection.RpcConnection;
import cn.managame.rpc.protocol.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** Delivers backend oneway responses and pushes to external client sessions. */
public final class GatewayRpcMessageHandler extends RpcMessageHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayRpcMessageHandler.class);
    private final GatewaySessionManager sessionManager;
    private final int loginCommand;

    public GatewayRpcMessageHandler(GatewaySessionManager sessionManager, int loginCommand) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        if (loginCommand <= 0) throw new IllegalArgumentException("loginCommand must be positive");
        this.loginCommand = loginCommand;
    }

    @Override
    protected void handleUserMsg(RpcConnection connection, RpcRequest message) {
        GatewaySession session = switch (message.getBusType()) {
            case GatewayRpcProtocol.BUS_TYPE_SESSION -> sessionManager.getBySessionId(message.getBusId());
            case GatewayRpcProtocol.BUS_TYPE_ROLE -> sessionManager.getByRoleId(message.getBusId());
            default -> null;
        };
        if (session == null || !session.getConnection().isActive()) {
            log.debug("dropping gateway downlink for absent session: busType={}, busId={}", message.getBusType(), message.getBusId());
            return;
        }
        int code = (int) Metadata.findLong(message.getMetadata(), MetadataKeys.GW_CODE, 0);
        if (message.getCommand() == loginCommand && code == 0) {
            long roleId = message.getRouteKey();
            if (roleId > 0 && session.getRoleId() != roleId) sessionManager.bindRole(session, roleId);
            session.setAuthenticated(true);
        }
        if (message.getCommand() == GatewaySessionManager.KICK_COMMAND) {
            sessionManager.kick(session, code);
            return;
        }
        int seq = (int) Metadata.findLong(message.getMetadata(), MetadataKeys.GW_SEQ, 0);
        byte[] body = message.getBody() instanceof byte[] bytes ? bytes : GatewayPacketConstant.EMPTY_BODY;
        GatewayPacket packet = GatewayPacket.of(message.getCommand(), seq, code, body);
        packet.setFlags((byte) Metadata.findLong(message.getMetadata(), MetadataKeys.GW_FLAGS, 0));
        session.writeMsg(packet);
    }
}
