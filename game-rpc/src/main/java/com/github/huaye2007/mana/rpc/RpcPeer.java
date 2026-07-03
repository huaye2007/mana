package com.github.huaye2007.mana.rpc;

import com.github.huaye2007.mana.rpc.connection.ConnectionGroup;
import com.github.huaye2007.mana.rpc.connection.RpcConnection;

/**
 * 一个对端服务实例（serviceName + serviceId）的视图：一组连接 + 在途调用管理。
 * 客户端按目标身份建 peer；服务端在握手后按对端身份建 peer，调用对等。
 */
public class RpcPeer {

    private final RpcContainer container;
    private String serviceName;
    private String serviceId;

    private final RpcInvokeManager rpcInvokeManager;
    private final ConnectionGroup connectionGroup = new ConnectionGroup();

    public RpcPeer(RpcContainer container, String serviceName, String serviceId) {
        this.container = container;
        this.serviceName = serviceName;
        this.serviceId = serviceId;
        this.rpcInvokeManager = new RpcInvokeManager(container);
    }

    public void add(RpcConnection connection) {
        connectionGroup.add(connection);
    }

    public void remove(RpcConnection connection) {
        connectionGroup.remove(connection);
    }

    public int connectionCount() {
        return connectionGroup.count();
    }

    public boolean isEmpty() {
        return connectionGroup.isEmpty();
    }

    public RpcConnection select(long routeKey) {
        return connectionGroup.select(routeKey);
    }

    public RpcConnection[] snapshot() {
        return connectionGroup.snapshot();
    }

    public void oneway(RpcRequest request) {
        RpcConnection connection = connectionGroup.select(request.getRouteKey());
        if (connection == null) {
            container.getMetrics().onRejectedNoConnection();
            return;
        }
        rpcInvokeManager.oneway(connection, request);
    }

    public RpcFuture invoke(RpcRequest request) {
        RpcConnection connection = connectionGroup.select(request.getRouteKey());
        if (connection == null) {
            container.getMetrics().onRejectedNoConnection();
            return RpcFuture.failed(noConnection());
        }
        return rpcInvokeManager.invoke(connection, request);
    }

    public <V> void invoke(RpcRequest request, RpcCallback<V> callback) {
        RpcConnection connection = connectionGroup.select(request.getRouteKey());
        if (connection == null) {
            container.getMetrics().onRejectedNoConnection();
            callback.onException(noConnection());
            return;
        }
        rpcInvokeManager.invoke(connection, request, callback);
    }

    private GameRpcException noConnection() {
        return new GameRpcException("no active connection to " + serviceName + "/" + serviceId);
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public RpcInvokeManager getRpcInvokeManager() {
        return rpcInvokeManager;
    }
}
