package cn.managame.gateway.registry;

import cn.managame.gateway.router.BackendRouterManager;
import cn.managame.gateway.rpc.GatewayRpcClient;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
import cn.managame.registry.api.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后端服务发现：watch 配置的后端服务名，把实例上下线映射为连接池增删 + 路由表刷新。
 *
 * <p>{@link ServiceRegistry#watchService} 在建立时同步发一遍现有实例的 ADDED 事件（初始快照），
 * 之后增量推送变更；因此 {@link #start} 不需要额外拉一次全量。事件在注册中心派发线程上
 * 回调，处理必须非阻塞——connect 只是异步发起 netty 建连，upsert/remove 只重建内存快照。</p>
 *
 * <p>上线次序：先建连再挂进路由表（尽量缩小"已可路由但连接还在握手"的窗口，该窗口内
 * 转发会 fast-fail 回错误帧）；下线次序：先摘出路由表再断连（先止血新流量）。</p>
 */
public class BackendDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(BackendDiscovery.class);

    private final ServiceRegistry registry;
    private final String backendServiceName;
    private final GatewayRpcClient rpcClient;
    private final BackendRouterManager routerManager;

    private AutoCloseable watchHandle;

    public BackendDiscovery(ServiceRegistry registry, String backendServiceName,
                            GatewayRpcClient rpcClient, BackendRouterManager routerManager) {
        this.registry = registry;
        this.backendServiceName = backendServiceName;
        this.rpcClient = rpcClient;
        this.routerManager = routerManager;
    }

    public void start() {
        watchHandle = registry.watchService(backendServiceName, this::onEvent);
        logger.info("watching backend service '{}', initial instances={}",
                backendServiceName, routerManager.instanceCount());
    }

    private void onEvent(ServiceInstanceEvent event) {
        ServiceInstance instance = event.getInstance();
        switch (event.getType()) {
            case ADDED, UPDATED -> {
                rpcClient.connectBackend(instance);
                routerManager.upsert(instance);
            }
            case REMOVED -> {
                routerManager.remove(instance);
                rpcClient.disconnectBackend(instance);
            }
        }
    }

    public void close() {
        if (watchHandle == null) {
            return;
        }
        try {
            watchHandle.close();
        } catch (Exception e) {
            logger.warn("close backend watch failed", e);
        }
        watchHandle = null;
    }
}
