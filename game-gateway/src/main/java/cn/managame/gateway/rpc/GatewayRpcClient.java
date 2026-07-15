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
    private final int connectionsPerBackend;

    public GatewayRpcClient(RpcClientConfig config, GatewayRpcMessageHandler handler,
                            int connectionsPerBackend) {
        if (connectionsPerBackend < 1) throw new IllegalArgumentException("connectionsPerBackend must be positive");
        this.client = new RpcClient(Objects.requireNonNull(config, "config"), Objects.requireNonNull(handler, "handler"));
        this.connectionsPerBackend = connectionsPerBackend;
    }

    @Override
    public void connectBackend(ServiceInstance instance) {
        ConnectionTargetConfig target = new ConnectionTargetConfig();
        target.setServiceName(instance.getName());
        target.setServiceId(instance.getKey());
        target.setIp(instance.getAddress());
        target.setPort(instance.getPort());
        target.setConnectionSize(connectionsPerBackend);
        client.connect(target);
    }

    @Override
    public void disconnectBackend(ServiceInstance instance) { client.disconnect(instance.getName(), instance.getKey()); }

    public void forward(String serviceName, String serviceId, RpcRequest request) {
        client.oneway(serviceName, serviceId, request);
    }

    public boolean tryForward(String serviceName, String serviceId, RpcRequest request) {
        return client.tryOneway(serviceName, serviceId, request);
    }

    public RpcClient unwrap() { return client; }

    @Override public void close() { client.close(); }
}
