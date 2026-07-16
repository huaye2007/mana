package cn.managame.gateway.bootstrap;

import cn.managame.gateway.codec.BodyCodec;
import cn.managame.gateway.filter.AuthFilter;
import cn.managame.gateway.filter.DdosFilter;
import cn.managame.gateway.filter.FilterChain;
import cn.managame.gateway.filter.RateLimitFilter;
import cn.managame.gateway.network.GatewayNetworkHandler;
import cn.managame.gateway.network.tcp.GatewayTcpServer;
import cn.managame.gateway.network.websocket.GatewayWebSocketServer;
import cn.managame.gateway.registry.BackendDiscovery;
import cn.managame.gateway.router.BackendDirectory;
import cn.managame.gateway.router.CommandBackendServiceResolver;
import cn.managame.gateway.router.ConsistentHashRouter;
import cn.managame.gateway.rpc.GatewayRpcClient;
import cn.managame.gateway.rpc.GatewayRpcMessageHandler;
import cn.managame.gateway.rpc.PacketForwarder;
import cn.managame.gateway.session.GatewaySessionManager;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceRegistry;
import cn.managame.registry.factory.RegistryConfig;
import cn.managame.registry.factory.RegistryFactory;
import cn.managame.rpc.RpcClientConfig;
import cn.managame.network.server.INetworkServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runnable gateway composition root. */
public final class Gateway implements AutoCloseable {
    private final GatewayConfig config;
    private final ServiceRegistry registry;
    private final boolean closeRegistry;
    private final GatewaySessionManager sessions;
    private final GatewayRpcClient rpcClient;
    private final List<BackendDiscovery> discoveries;
    private final List<INetworkServer> networkServers;
    private final ServiceInstance self;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public Gateway(GatewayConfig config, ServiceRegistry registry) { this(config, registry, false); }

    private Gateway(GatewayConfig config, ServiceRegistry registry, boolean closeRegistry) {
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.closeRegistry = closeRegistry;
        this.sessions = new GatewaySessionManager(config.serverId());
        BackendDirectory backends = new BackendDirectory(ConsistentHashRouter::new);
        CommandBackendServiceResolver serviceResolver = CommandBackendServiceResolver.parse(
                config.backendService(), config.backendRoutes());
        GatewayRpcMessageHandler downlink = new GatewayRpcMessageHandler(sessions, config.loginCommand());
        RpcClientConfig rpcConfig = new RpcClientConfig()
                .serviceName(config.serviceName()).serviceId(config.instanceId())
                .authToken(config.rpcAuthToken());
        rpcClient = new GatewayRpcClient(rpcConfig, downlink, config.backendConnections());
        discoveries = serviceResolver.serviceNames().stream()
                .map(service -> new BackendDiscovery(registry, service, backends.service(service), rpcClient))
                .toList();
        PacketForwarder forwarder = new PacketForwarder(rpcClient, backends, serviceResolver, config.loginCommand());
        FilterChain filters = new FilterChain(List.of(
                new DdosFilter(config.ddosMaxConnectionsPerIp(), config.ddosPpsPerIp(), config.ddosBurstPerIp()),
                new RateLimitFilter(config.ratePps(), config.rateBurst()),
                new AuthFilter(config.loginCommand())));
        GatewayNetworkHandler network = new GatewayNetworkHandler(sessions, filters, forwarder);
        List<INetworkServer> servers = new ArrayList<>();
        servers.add(new GatewayTcpServer(config.tcpPort(), config.readerIdleSeconds(), BodyCodec.IDENTITY, network));
        if (config.webSocketEnabled()) {
            servers.add(new GatewayWebSocketServer(config.wsPort(), config.wsPath(), BodyCodec.IDENTITY, network));
        }
        networkServers = List.copyOf(servers);
        self = ServiceInstance.builder().name(config.serviceName()).id(config.instanceId())
                .address(config.advertiseAddress()).port(config.tcpPort())
                .metadata(config.webSocketEnabled()
                        ? Map.of("ws.port", Integer.toString(config.wsPort()), "ws.path", config.wsPath()) : Map.of())
                .build();
    }

    public static Gateway create(GatewayConfig config) {
        ServiceRegistry registry = RegistryFactory.startRegistry(RegistryConfig.builder()
                .type(config.registryType()).endpoints(config.registryEndpoints()).build());
        try { return new Gateway(config, registry, true); }
        catch (RuntimeException error) { registry.close(); throw error; }
    }

    public void start() {
        if (closed.get()) throw new IllegalStateException("gateway is closed");
        if (!running.compareAndSet(false, true)) return;
        try {
            discoveries.forEach(BackendDiscovery::start);
            networkServers.forEach(INetworkServer::start);
            registry.register(self);
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    public boolean isRunning() { return running.get(); }
    public GatewaySessionManager sessions() { return sessions; }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        running.set(false);
        try { registry.deregister(self); } catch (RuntimeException ignored) { }
        for (int i = networkServers.size() - 1; i >= 0; i--) {
            try { networkServers.get(i).stop(); } catch (RuntimeException ignored) { }
        }
        sessions.closeAll();
        for (int i = discoveries.size() - 1; i >= 0; i--) {
            try { discoveries.get(i).close(); } catch (RuntimeException ignored) { }
        }
        try { rpcClient.close(); } finally { if (closeRegistry) registry.close(); }
    }

    public static void main(String[] args) throws InterruptedException {
        Gateway gateway = Gateway.create(GatewayConfig.load(args));
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("game-gateway-shutdown").unstarted(() -> {
            gateway.close();
            stopped.countDown();
        }));
        gateway.start();
        stopped.await();
    }
}
