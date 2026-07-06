package cn.managame.gateway.router;

import cn.managame.gateway.session.GatewaySession;
import cn.managame.registry.api.ServiceInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后端实例表 + 会话粘滞路由。
 *
 * <p>实例表由注册中心 watch 驱动（{@link #upsert}/{@link #remove}），
 * 每次变更整体重建 {@link Router} 快照。会话首包经策略选定实例后粘在
 * 该实例上（记在 {@code GatewaySession.backendServiceId}），此后所有包
 * 都发往同一实例；实例下线才重选并重置登录态（玩家态在旧实例上，
 * 换实例必须重新走登录）。</p>
 */
public class BackendRouterManager {

    private static final Logger logger = LoggerFactory.getLogger(BackendRouterManager.class);

    private final Router router;
    private final Map<String, ServiceInstance> instanceMap = new ConcurrentHashMap<>();

    public BackendRouterManager(Router router) {
        this.router = router;
    }

    /** 实例上线/元数据变更（注册中心 watch 线程）。 */
    public void upsert(ServiceInstance instance) {
        instanceMap.put(instance.getKey(), instance);
        rebuild();
    }

    /** 实例下线（注册中心 watch 线程）。 */
    public void remove(ServiceInstance instance) {
        instanceMap.remove(instance.getKey());
        rebuild();
    }

    public ServiceInstance get(String serviceId) {
        return instanceMap.get(serviceId);
    }

    public boolean isAlive(String serviceId) {
        return serviceId != null && instanceMap.containsKey(serviceId);
    }

    public int instanceCount() {
        return instanceMap.size();
    }

    /**
     * 为会话解析后端实例：已粘滞且实例仍在线直接返回；否则按策略重选并粘滞。
     * 无可用实例返回 null。IO 线程调用。
     */
    public ServiceInstance resolve(GatewaySession session) {
        String bound = session.getBackendServiceId();
        if (bound != null) {
            ServiceInstance instance = instanceMap.get(bound);
            if (instance != null) {
                return instance;
            }
            // 粘滞实例已下线：登录态随实例失效，客户端需要重新登录
            logger.warn("sticky backend {} gone, session {} reset to unauthenticated",
                    bound, session.getSessionId());
            session.setBackendServiceId(null);
            session.setAuthenticated(false);
        }
        ServiceInstance selected = router.select(session.routeKey());
        if (selected != null) {
            session.setBackendServiceId(selected.getKey());
        }
        return selected;
    }

    private void rebuild() {
        router.refresh(new ArrayList<>(instanceMap.values()));
    }
}
