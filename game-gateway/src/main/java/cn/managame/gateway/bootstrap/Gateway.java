package cn.managame.gateway.bootstrap;

import cn.managame.gateway.codec.BodyCodec;
import cn.managame.gateway.filter.GatewayGuard;
import cn.managame.gateway.network.GatewayNetworkHandler;
import cn.managame.gateway.network.tcp.GatewayTcpServer;
import cn.managame.gateway.network.websocket.GatewayWebSocketServer;
import cn.managame.gateway.router.BackendDirectory;
import cn.managame.gateway.router.CommandBackendServiceResolver;
import cn.managame.gateway.rpc.GatewayRpcClient;
import cn.managame.gateway.rpc.GatewayRpcMessageHandler;
import cn.managame.gateway.rpc.PacketForwarder;
import cn.managame.gateway.session.GatewaySessionManager;
import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
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

/** Runnable gateway composition root. */
public final class Gateway implements AutoCloseable {
    private final GatewayConfig config;
    private final ServiceRegistry registry;
    private final boolean closeRegistry;
    private final GatewaySessionManager sessions;
    private final BackendDirectory backends;
    private final GatewayRpcClient rpcClient;
    private final List<String> backendServices;
    private final List<AutoCloseable> backendWatches = new ArrayList<>();
    private final Object backendLock = new Object();
    private final List<INetworkServer> networkServers;
    private final ServiceInstance self;
    private volatile boolean running;
    private volatile boolean closed;

    public Gateway(GatewayConfig config, ServiceRegistry registry) { this(config, registry, false); }

    private Gateway(GatewayConfig config, ServiceRegistry registry, boolean closeRegistry) {
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.closeRegistry = closeRegistry;
        GatewayConfig.Transport transport = config.transport();
        GatewayConfig.Identity identity = config.identity();
        GatewayConfig.Backend backend = config.backend();
        GatewayConfig.Limits limits = config.limits();
        this.sessions = new GatewaySessionManager(identity.serverId());
        this.backends = new BackendDirectory();
        CommandBackendServiceResolver serviceResolver = CommandBackendServiceResolver.parse(
                backend.service(), backend.routes());
        GatewayRpcMessageHandler downlink = new GatewayRpcMessageHandler(sessions, backend.loginCommand());
        RpcClientConfig rpcConfig = new RpcClientConfig()
                .serviceName(identity.serviceName()).serviceId(identity.instanceId())
                .authToken(backend.rpcAuthToken());
        rpcClient = new GatewayRpcClient(rpcConfig, downlink, backend.connections());
        backendServices = List.copyOf(serviceResolver.serviceNames());
        PacketForwarder forwarder = new PacketForwarder(rpcClient, backends, serviceResolver, backend.loginCommand());
        GatewayGuard guard = new GatewayGuard(backend.loginCommand(), limits.maxConnectionsPerIp(),
                limits.sessionPps(), limits.sessionBurst(), limits.ipPps(), limits.ipBurst());
        GatewayNetworkHandler network = new GatewayNetworkHandler(sessions, guard, forwarder);
        List<INetworkServer> servers = new ArrayList<>();
        servers.add(new GatewayTcpServer(transport.tcpPort(), transport.readerIdleSeconds(),
                BodyCodec.IDENTITY, network));
        if (transport.webSocketEnabled()) {
            servers.add(new GatewayWebSocketServer(transport.webSocketPort(), transport.webSocketPath(),
                    BodyCodec.IDENTITY, network));
        }
        networkServers = List.copyOf(servers);
        self = ServiceInstance.builder().name(identity.serviceName()).id(identity.instanceId())
                .address(identity.advertiseAddress()).port(transport.tcpPort())
                .metadata(transport.webSocketEnabled()
                        ? Map.of("ws.port", Integer.toString(transport.webSocketPort()),
                                "ws.path", transport.webSocketPath())
                        : Map.of())
                .build();
    }

    public static Gateway create(GatewayConfig config) {
        GatewayConfig.Registry registryConfig = config.registry();
        ServiceRegistry registry = RegistryFactory.startRegistry(RegistryConfig.builder()
                .type(registryConfig.type()).endpoints(registryConfig.endpoints()).build());
        try { return new Gateway(config, registry, true); }
        catch (RuntimeException error) { registry.close(); throw error; }
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("gateway is closed");
        if (running) return;
        running = true;
        try {
            for (String service : backendServices) {
                backendWatches.add(registry.watchService(service, this::onBackendEvent));
            }
            networkServers.forEach(INetworkServer::start);
            registry.register(self);
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    public boolean isRunning() { return running; }
    public GatewaySessionManager sessions() { return sessions; }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        running = false;
        try { registry.deregister(self); } catch (RuntimeException ignored) { }
        for (int i = networkServers.size() - 1; i >= 0; i--) {
            try { networkServers.get(i).stop(); } catch (RuntimeException ignored) { }
        }
        sessions.closeAll();
        for (int i = backendWatches.size() - 1; i >= 0; i--) {
            try { backendWatches.get(i).close(); } catch (Exception ignored) { }
        }
        backendWatches.clear();
        try {
            synchronized (backendLock) { rpcClient.close(); }
        } finally {
            if (closeRegistry) registry.close();
        }
    }

    /** Applies discovery events supplied by game-registry to the local route and RPC pools. */
    private void onBackendEvent(ServiceInstanceEvent event) {
        synchronized (backendLock) {
            if (closed) return;
            ServiceInstance incoming = event.getInstance();
            ServiceInstance previous = backends.get(incoming.getName(), incoming.getKey());
            if (event.getType() == DiscoveryEventType.REMOVED || !incoming.isHealthy()) {
                ServiceInstance removed = previous != null ? previous : incoming;
                backends.remove(removed);
                rpcClient.disconnectBackend(removed);
                return;
            }

            boolean endpointChanged = previous != null
                    && (!previous.getAddress().equals(incoming.getAddress()) || previous.getPort() != incoming.getPort());
            if (endpointChanged) {
                backends.remove(previous);
                rpcClient.disconnectBackend(previous);
            }
            if (previous == null || endpointChanged) rpcClient.connectBackend(incoming);
            backends.upsert(incoming);
        }
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
