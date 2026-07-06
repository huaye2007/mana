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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外网接入的统一入口，TCP 与 WebSocket 两个 server 共用一个实例。
 *
 * <p>连接：建 {@link GatewaySession} → 登记会话表 → 过滤链 {@code onConnect}（IP 防护），
 * 拒绝则关连接。入站包：过滤链 {@code onPacket}（DDoS/限流/登录 gate），放行的交给
 * {@link PacketForwarder} 转发到后端；拦下的按错误码回一帧空 body。断线：过滤链清理 +
 * 会话表解绑。所有回调都在网络 IO 线程，逻辑保持轻量。</p>
 */
public class GatewayNetworkHandler implements INetworkHandler {

    private static final Logger logger = LoggerFactory.getLogger(GatewayNetworkHandler.class);

    private final GatewaySessionManager sessionManager;
    private final FilterChain filterChain;
    private final PacketForwarder forwarder;

    public GatewayNetworkHandler(GatewaySessionManager sessionManager, FilterChain filterChain,
                                 PacketForwarder forwarder) {
        this.sessionManager = sessionManager;
        this.filterChain = filterChain;
        this.forwarder = forwarder;
    }

    @Override
    public ISession createSession(IConnection connection) {
        return new GatewaySession(connection, extractIp(connection.getRemoteAddress()));
    }

    @Override
    public void onConnect(ISession session) {
        GatewaySession gatewaySession = (GatewaySession) session;
        sessionManager.add(gatewaySession);
        if (!filterChain.onConnect(gatewaySession)) {
            // IP 连接数超限等：过滤链已回滚各自计数，这里直接断开
            sessionManager.remove(gatewaySession);
            session.close();
        }
    }

    @Override
    public void onMessage(ISession session, Object packet) {
        GatewaySession gatewaySession = (GatewaySession) session;
        GatewayPacket gatewayPacket = (GatewayPacket) packet;
        int code = filterChain.onPacket(gatewaySession, gatewayPacket);
        if (code != GatewayErrorCode.OK) {
            gatewaySession.writeMsg(GatewayPacket.of(gatewayPacket.getCommand(), gatewayPacket.getSeq(),
                    code, GatewayPacketConstant.EMPTY_BODY));
            return;
        }
        forwarder.forward(gatewaySession, gatewayPacket);
    }

    @Override
    public void onDisconnect(ISession session) {
        GatewaySession gatewaySession = (GatewaySession) session;
        filterChain.onDisconnect(gatewaySession);
        sessionManager.remove(gatewaySession);
    }

    @Override
    public void onException(ISession session, Throwable cause) {
        logger.warn("session exception, closing connection", cause);
        session.close();
    }

    /** {@code IConnection.getRemoteAddress()} 返回 host:port，DDoS 按 IP 聚合，去掉端口。 */
    private static String extractIp(String remoteAddress) {
        if (remoteAddress == null) {
            return "unknown";
        }
        int colon = remoteAddress.lastIndexOf(':');
        return colon > 0 ? remoteAddress.substring(0, colon) : remoteAddress;
    }
}
