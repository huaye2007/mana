package cn.managame.gateway.rpc;

import cn.managame.common.context.MetadataKeys;
import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketConstant;
import cn.managame.gateway.router.BackendRouterManager;
import cn.managame.gateway.session.GatewaySession;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.rpc.GameRpcException;
import cn.managame.rpc.RpcRequest;
import cn.managame.rpc.protocol.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行转发：把客户端外网帧封成 game-rpc oneway 帧发往粘滞后端实例。
 *
 * <p>用 oneway 而非 invoke——网关是纯管道，一条上行请求可能对应零条/一条/多条下行
 * （回包、推送、广播），由后端决定，回程走 {@link GatewayRpcMessageHandler}。
 * 无可用后端或连接不可用时，直接给客户端回错误帧（转发失败不重试）。</p>
 */
public class PacketForwarder {

    private static final Logger logger = LoggerFactory.getLogger(PacketForwarder.class);

    private final GatewayRpcClient rpcClient;
    private final BackendRouterManager routerManager;
    private final int loginCommand;

    public PacketForwarder(GatewayRpcClient rpcClient, BackendRouterManager routerManager, int loginCommand) {
        this.rpcClient = rpcClient;
        this.routerManager = routerManager;
        this.loginCommand = loginCommand;
    }

    /** IO 线程调用：解析后端 → 组转发帧 → oneway 发送；失败回错误帧。 */
    public void forward(GatewaySession session, GatewayPacket packet) {
        ServiceInstance backend = routerManager.resolve(session);
        if (backend == null) {
            logger.warn("no backend for session {}, command={}", session.getSessionId(), packet.getCommand());
            replyError(session, packet, GatewayErrorCode.NO_BACKEND);
            return;
        }

        // 会话定位走 busType/busId：已绑定角色带 roleId，否则带 sessionId（后端回程据此回带定位会话）
        long roleId = session.getRoleId();
        RpcRequest request = RpcRequest.oneway(packet.getCommand())
                .routeKey(session.routeKey())
                .serialType(GatewayPacketConstant.BODY_SERIAL_TYPE)
                .busType(roleId != 0 ? GatewayRpcProtocol.BUS_TYPE_ROLE : GatewayRpcProtocol.BUS_TYPE_SESSION)
                .busId(roleId != 0 ? roleId : session.getSessionId())
                .body(packet.getBody())
                .metadata(buildMetadata(session, packet));

        try {
            rpcClient.forward(backend.getKey(), request);
        } catch (GameRpcException e) {
            // peer 尚未握手完成 / 连接全断等：回错误帧让客户端 fast-fail
            logger.warn("forward failed, session={}, backend={}, command={}: {}",
                    session.getSessionId(), backend.getKey(), packet.getCommand(), e.getMessage());
            replyError(session, packet, GatewayErrorCode.GATEWAY_ERROR);
        }
    }

    /**
     * 上行 metadata：seq 必带（下行原样回 echo），登录命令额外带 clientIp 供后端风控。
     * 会话定位不走 metadata——见 {@link #forward} 里设置的 busType/busId。
     */
    private Metadata[] buildMetadata(GatewaySession session, GatewayPacket packet) {
        Metadata seqMeta = Metadata.ofLong(MetadataKeys.GW_SEQ, packet.getSeq());
        if (packet.getCommand() == loginCommand) {
            return new Metadata[]{seqMeta,
                    Metadata.ofString(MetadataKeys.GW_CLIENT_IP, session.getClientIp())};
        }
        return new Metadata[]{seqMeta};
    }

    private static void replyError(GatewaySession session, GatewayPacket packet, int code) {
        session.writeMsg(GatewayPacket.of(packet.getCommand(), packet.getSeq(),
                code, GatewayPacketConstant.EMPTY_BODY));
    }
}
