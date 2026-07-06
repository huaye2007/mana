package cn.managame.gateway.rpc;

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

/**
 * 后端下行消息处理器：把后端经 RPC 连接推回来的 oneway 消息还原成外网帧写给对应客户端。
 *
 * <p>定位目标会话：按 RPC 帧 {@code busType} 选索引——{@link GatewayRpcProtocol#BUS_TYPE_ROLE}
 * 用 {@code busId} 作 roleId 查，否则作 sessionId 查；找不到（已断线）则丢弃。后端在登录响应用
 * {@code routeKey} 回带 roleId，触发首次 roleId→会话 绑定（顶号在
 * {@link GatewaySessionManager#bindRole} 内处理）。登录命令且 code=0 视为"登录数据包校验通过"，
 * 翻转会话认证态，此后放行其余命令。收到踢下线命令则写完帧后关连接。全部在 RPC IO 线程执行，保持轻量。</p>
 */
public class GatewayRpcMessageHandler extends RpcMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(GatewayRpcMessageHandler.class);

    private final GatewaySessionManager sessionManager;
    private final int loginCommand;

    public GatewayRpcMessageHandler(GatewaySessionManager sessionManager, int loginCommand) {
        this.sessionManager = sessionManager;
        this.loginCommand = loginCommand;
    }

    @Override
    protected void handleUserMsg(RpcConnection rpcConnection, RpcRequest msg) {
        long busId = msg.getBusId();
        GatewaySession session = msg.getBusType() == GatewayRpcProtocol.BUS_TYPE_ROLE
                ? sessionManager.getByRoleId(busId)
                : sessionManager.getBySessionId(busId);
        if (session == null) {
            // 客户端已断线，回程消息无处投递，丢弃（转发失败不重试）
            logger.debug("downstream msg dropped, no session, busType={}, busId={}, command={}",
                    msg.getBusType(), busId, msg.getCommand());
            return;
        }

        // 后端登录响应用 routeKey 回带 roleId：首次绑定 roleId→会话，顶号时旧连接被踢
        long roleId = msg.getRouteKey();
        if (roleId != 0 && session.getRoleId() == 0) {
            sessionManager.bindRole(session, roleId);
        }

        Metadata[] metadata = msg.getMetadata();
        int command = msg.getCommand();
        int seq = (int) Metadata.findLong(metadata, GatewayRpcProtocol.MK_SEQ, 0);
        int code = (int) Metadata.findLong(metadata, GatewayRpcProtocol.MK_CODE, 0);

        // 登录数据包校验通过：翻转认证态，AuthFilter 此后放行其余命令
        if (command == loginCommand && code == 0) {
            session.setAuthenticated(true);
        }

        byte[] body = bodyOf(msg);

        // 踢下线：写完原因帧后关连接（沿用 KICK_COMMAND 语义）
        if (command == GatewaySessionManager.KICK_COMMAND) {
            sessionManager.kick(session, code);
            return;
        }

        session.writeMsg(GatewayPacket.of(command, seq, code, body));
    }

    /** 入站 RpcRequest 的 body 恒为 byte[]（RpcCodec 保证）；空 body 归一化为 EMPTY_BODY。 */
    private static byte[] bodyOf(RpcRequest msg) {
        Object body = msg.getBody();
        if (body == null) {
            return GatewayPacketConstant.EMPTY_BODY;
        }
        if (body instanceof byte[] bytes) {
            return bytes;
        }
        // 不应发生：入站 body 由解码器产出，恒为 byte[]
        logger.warn("downstream body is not byte[]: {}, command={}", body.getClass().getName(), msg.getCommand());
        return GatewayPacketConstant.EMPTY_BODY;
    }
}
