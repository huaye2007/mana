package cn.managame.gateway.rpc;

import cn.managame.registry.api.ServiceInstance;
import cn.managame.rpc.ConnectionTargetConfig;
import cn.managame.rpc.RpcClient;
import cn.managame.rpc.RpcClientConfig;
import cn.managame.rpc.RpcRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关内网转发客户端：复用 game-rpc 的 {@link RpcClient}（每后端一组连接池、写空闲心跳、
 * 断线自愈重连、协议复用）。后端实例上下线由 {@code BackendDiscovery} 驱动
 * {@link #connectBackend}/{@link #disconnectBackend}。
 *
 * <p>转发目标按 {@code (backendServiceName, serviceId=instance.getKey())} 定位到对应
 * peer，再按 routeKey 从该 peer 的连接池选一条连接发送。</p>
 */
public class GatewayRpcClient {

    private static final Logger logger = LoggerFactory.getLogger(GatewayRpcClient.class);

    private final RpcClient rpcClient;
    private final String backendServiceName;
    private final int connectionSizePerBackend;

    public GatewayRpcClient(RpcClientConfig config, GatewayRpcMessageHandler handler,
                            String backendServiceName, int connectionSizePerBackend) {
        this.rpcClient = new RpcClient(config, handler);
        this.backendServiceName = backendServiceName;
        this.connectionSizePerBackend = connectionSizePerBackend;
    }

    /** 后端实例上线：建立到该实例的连接池（对同一实例幂等）。 */
    public void connectBackend(ServiceInstance instance) {
        ConnectionTargetConfig target = new ConnectionTargetConfig();
        target.setServiceName(backendServiceName);
        target.setServiceId(instance.getKey());
        target.setIp(instance.getAddress());
        target.setPort(instance.getPort());
        target.setConnectionSize(connectionSizePerBackend);
        rpcClient.connect(target);
        logger.info("connect backend {}/{} at {}:{}", backendServiceName, instance.getKey(),
                instance.getAddress(), instance.getPort());
    }

    /** 后端实例下线：停重连、失败在途、关连接。 */
    public void disconnectBackend(ServiceInstance instance) {
        rpcClient.disconnect(backendServiceName, instance.getKey());
        logger.info("disconnect backend {}/{}", backendServiceName, instance.getKey());
    }

    /**
     * 向指定后端实例发一条 oneway 转发帧（按 routeKey 从连接池选连接）。
     * 无可用 peer/连接时抛 {@link cn.managame.rpc.GameRpcException}，由调用方回错误帧。
     */
    public void forward(String serviceId, RpcRequest request) {
        rpcClient.oneway(backendServiceName, serviceId, request);
    }

    public void close() {
        rpcClient.close();
    }
}
