package cn.managame.gateway.rpc;

import cn.managame.gateway.registry.BackendConnector;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.rpc.ConnectionTargetConfig;
import cn.managame.rpc.RpcClient;
import cn.managame.rpc.RpcClientConfig;
import cn.managame.rpc.RpcRequest;

import java.util.Objects;

/** Gateway-specific facade over the generic RPC client's target pool. */
public final class GatewayRpcClient implements BackendConnector, AutoCloseable {
    private final RpcClient client;
    private final String backendServiceName;
    private final int connectionsPerBackend;

    public GatewayRpcClient(RpcClientConfig config, GatewayRpcMessageHandler handler,
                            String backendServiceName, int connectionsPerBackend) {
        if (backendServiceName == null || backendServiceName.isBlank()) throw new IllegalArgumentException("backendServiceName must not be blank");
        if (connectionsPerBackend < 1) throw new IllegalArgumentException("connectionsPerBackend must be positive");
        this.client = new RpcClient(Objects.requireNonNull(config, "config"), Objects.requireNonNull(handler, "handler"));
        this.backendServiceName = backendServiceName;
        this.connectionsPerBackend = connectionsPerBackend;
    }

    @Override
    public void connectBackend(ServiceInstance instance) {
        ConnectionTargetConfig target = new ConnectionTargetConfig();
        target.setServiceName(backendServiceName);
        target.setServiceId(instance.getKey());
        target.setIp(instance.getAddress());
        target.setPort(instance.getPort());
        target.setConnectionSize(connectionsPerBackend);
        client.connect(target);
    }

    @Override
    public void disconnectBackend(ServiceInstance instance) { client.disconnect(backendServiceName, instance.getKey()); }

    public void forward(String serviceId, RpcRequest request) { client.oneway(backendServiceName, serviceId, request); }

    public RpcClient unwrap() { return client; }

    @Override public void close() { client.close(); }
}
