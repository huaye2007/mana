package cn.managame.gateway.bootstrap;

import cn.managame.config.manager.GameConfigManager;
import cn.managame.config.starter.GameConfigStarter;
import cn.managame.gateway.codec.BodyCodec;
import cn.managame.gateway.filter.AuthFilter;
import cn.managame.gateway.filter.DdosFilter;
import cn.managame.gateway.filter.FilterChain;
import cn.managame.gateway.filter.GatewayFilter;
import cn.managame.gateway.filter.RateLimitFilter;
import cn.managame.gateway.network.GatewayNetworkHandler;
import cn.managame.gateway.network.tcp.GatewayTcpServer;
import cn.managame.gateway.network.websocket.GatewayWebSocketServer;
import cn.managame.gateway.registry.BackendDiscovery;
import cn.managame.gateway.router.BackendRouterManager;
import cn.managame.gateway.router.ConsistentHashRouter;
import cn.managame.gateway.rpc.GatewayRpcClient;
import cn.managame.gateway.rpc.GatewayRpcMessageHandler;
import cn.managame.gateway.rpc.PacketForwarder;
import cn.managame.gateway.session.GatewaySessionManager;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.factory.RegistryBundle;
import cn.managame.registry.starter.GameRegistryStarter;
import cn.managame.rpc.RpcClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 网关启动类：装配 会话表 → 路由/连接池 → 过滤链 → 外网 TCP/WS 服务端 → 后端服务发现，
 * 并把自身注册到注册中心。装配顺序保证"先能收发再对外可见"，停机按逆序收敛到单一 hook。
 */
public final class Gateway {

    private static final Logger logger = LoggerFactory.getLogger(Gateway.class);

    private Gateway() {
    }

    public static void main(String[] args) {
        GameConfigManager configManager = GameConfigStarter.builder()
                .args(args)
                .systemProperties(true)
                .environmentVariables(true)
                .localFile(GameConfigStarter.DEFAULT_LOCAL_FILE)
                .start();
        GatewayConfig config = GatewayConfig.load(configManager);
        String gatewayId = config.getServiceName() + "-" + config.getServerId();

        // 会话表：TCP / WS 两个 server 共用，后端推送不关心接入方式
        GatewaySessionManager sessionManager = new GatewaySessionManager();

        // 路由 + 连接池：一致性哈希（同会话粘同后端），后端上下线时重建
        BackendRouterManager routerManager = new BackendRouterManager(new ConsistentHashRouter());

        // 内网 RPC 客户端：下行推送经 handler 还原成外网帧写回客户端
        GatewayRpcMessageHandler rpcHandler = new GatewayRpcMessageHandler(sessionManager, config.getLoginCommand());
        RpcClientConfig rpcClientConfig = new RpcClientConfig()
                .serviceName(config.getServiceName())
                .serviceId(gatewayId)
                .connectionSize(config.getBackendConnections());
        GatewayRpcClient rpcClient = new GatewayRpcClient(rpcClientConfig, rpcHandler,
                config.getBackendService(), config.getBackendConnections());
        PacketForwarder forwarder = new PacketForwarder(rpcClient, routerManager, config.getLoginCommand());

        // 责任链：IP 级 DDoS（最便宜先挡）→ 会话级限流 → 登录 gate
        FilterChain filterChain = new FilterChain(List.<GatewayFilter>of(
                new DdosFilter(config.getDdosMaxConnPerIp(), config.getDdosPpsPerIp(), config.getDdosBurstPerIp()),
                new RateLimitFilter(config.getRatePps(), config.getRateBurst()),
                new AuthFilter(config.getLoginCommand())));

        BodyCodec bodyCodec = BodyCodec.IDENTITY; // 接入加密/压缩时替换
        GatewayNetworkHandler networkHandler = new GatewayNetworkHandler(sessionManager, filterChain, forwarder);

        // 外网服务端：TCP 必开，WS 按配置可选
        GatewayTcpServer tcpServer = new GatewayTcpServer(config.getTcpPort(), config.getServerId(),
                config.getReaderIdleSeconds(), bodyCodec, networkHandler);
        GatewayWebSocketServer wsServer = config.isWebSocketEnabled()
                ? new GatewayWebSocketServer(config.getWsPort(), config.getWsPath(),
                        config.getServerId(), bodyCodec, networkHandler)
                : null;

        RegistryBundle registryBundle = GameRegistryStarter.builder()
                .type(config.getRegistryType())
                .endpoints(config.getRegistryEndpoints())
                .start();
        BackendDiscovery backendDiscovery = new BackendDiscovery(registryBundle.getDiscovery(),
                config.getBackendService(), rpcClient, routerManager);

        // 端口就绪 → watch 后端建连接池 → 最后注册自身（先能收发再对外可见）
        try {
            tcpServer.start();
            if (wsServer != null) {
                wsServer.start();
            }
            backendDiscovery.start();
            registerSelf(registryBundle, config, gatewayId);
        } catch (RuntimeException e) {
            logger.error("gateway start failed, rolling back", e);
            safeClose(backendDiscovery, registryBundle, tcpServer, wsServer, rpcClient, configManager);
            throw e;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> shutdown(backendDiscovery, registryBundle, tcpServer, wsServer, rpcClient, configManager),
                "gateway-shutdown"));
        logger.info("gateway started, tcp={}, ws={}, backend='{}', registry={}",
                config.getTcpPort(), config.isWebSocketEnabled() ? config.getWsPort() : "off",
                config.getBackendService(), config.getRegistryType());
    }

    private static void registerSelf(RegistryBundle bundle, GatewayConfig config, String gatewayId) {
        ServiceInstance instance = ServiceInstance.builder()
                .name(config.getServiceName())
                .id(gatewayId)
                .address(config.getAdvertiseAddress())
                .port(config.getTcpPort())
                .build();
        bundle.getRegistry().register(instance);
        logger.info("registered gateway instance {}", instance);
    }

    /**
     * 停机序列：先停后端发现（不再响应后端变更）→ 关注册中心（注销自身、停 watch）→
     * 停外网服务端（关客户端连接）→ 关内网 RPC 客户端（排空在途、关后端连接）→ 关配置。
     */
    private static void shutdown(BackendDiscovery backendDiscovery, RegistryBundle registryBundle,
                                 GatewayTcpServer tcpServer, GatewayWebSocketServer wsServer,
                                 GatewayRpcClient rpcClient, GameConfigManager configManager) {
        logger.info("gateway shutting down");
        safeClose(backendDiscovery, registryBundle, tcpServer, wsServer, rpcClient, configManager);
        logger.info("gateway shutdown complete");
    }

    private static void safeClose(BackendDiscovery backendDiscovery, RegistryBundle registryBundle,
                                  GatewayTcpServer tcpServer, GatewayWebSocketServer wsServer,
                                  GatewayRpcClient rpcClient, GameConfigManager configManager) {
        step("close backend discovery", backendDiscovery::close);
        step("close registry", registryBundle::close);
        step("stop tcp server", tcpServer::stop);
        if (wsServer != null) {
            step("stop ws server", wsServer::stop);
        }
        step("close rpc client", rpcClient::close);
        step("close config manager", configManager::close);
    }

    private static void step(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            logger.error("{} failed", what, e);
        }
    }
}
