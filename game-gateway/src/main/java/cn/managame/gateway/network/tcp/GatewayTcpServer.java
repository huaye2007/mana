package cn.managame.gateway.network.tcp;

import cn.managame.gateway.codec.BodyCodec;
import cn.managame.gateway.network.GatewayNetworkHandler;
import cn.managame.network.connection.ServerConnectionIdGenerator;
import cn.managame.network.server.NettyTcpServer;
import cn.managame.network.server.NetworkTcpServerConfig;

/**
 * 网关外网 TCP 服务端：客户端明文/加密二进制流走这里。
 * 组合 game-network 的 {@link NettyTcpServer}，把 {@link GatewayTcpPipelineConfigurator}
 * 作为 beforeDispatcher 注入（读空闲 + 帧编解码），业务派发器把 {@link cn.managame.gateway.codec.GatewayPacket}
 * 交给 {@link GatewayNetworkHandler}。
 */
public class GatewayTcpServer {

    private final NettyTcpServer server;

    public GatewayTcpServer(int port, int serverId, int readerIdleSeconds,
                            BodyCodec bodyCodec, GatewayNetworkHandler networkHandler) {
        NetworkTcpServerConfig config = new NetworkTcpServerConfig(port);
        this.server = new NettyTcpServer(config, networkHandler,
                new ServerConnectionIdGenerator(serverId),
                new GatewayTcpPipelineConfigurator(readerIdleSeconds, bodyCodec));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop();
    }
}
