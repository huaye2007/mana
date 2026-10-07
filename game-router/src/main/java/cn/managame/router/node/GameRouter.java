package cn.managame.router.node;

import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.call.*;
import cn.managame.rpc.message.*;
import cn.managame.router.call.RouterHandler;
import cn.managame.router.route.*;
import java.util.*;

/**
 * Routing assembly entry point and ordinary RPC Handler for the forwarding role.
 * The forwarding role exposes Router membership; the service role returns concrete service routing.
 * Construct before the RPC Node and associate it through start; the application closes both.
 * Neither role creates, starts or closes the RPC endpoint.
 */
public final class GameRouter implements RpcHandler, AutoCloseable {
    private final RouterEngine engine;
    private final RpcHandler messages;
    private GameRouter(long routerEpoch, RpcHandler directHandler) {
        engine = new RouterEngine(routerEpoch);
        messages = new RoutingRpcHandler(Objects.requireNonNull(directHandler), engine, engine::ownsCallback);
    }
    public static GameRouter forRouterNode(long routerEpoch, RpcHandler directHandler) {
        return new GameRouter(routerEpoch, directHandler);
    }
    public static ServiceRouting forServiceNode(int serviceId, RouterHandler handler, RpcHandler directHandler) {
        return new ServiceRouting(serviceId, handler, directHandler);
    }
    /** Associates the application-owned Node once and starts routing maintenance, not networking. */
    public void start(RpcNode rpc) { engine.start(rpc); }
    @Override public void onRequest(int source, int slot, RpcRequest request) { messages.onRequest(source, slot, request); }
    @Override public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) { messages.onResponse(source, command, response, callback); }
    @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) { messages.onFail(target, command, error, callback); }
    @Override public void close() { engine.close(); }
    /** Declares a routing peer. Connections are configured separately through RpcNode.addPeer. */
    public void registerRouter(int id) { engine.registerRouter(id); }
    /** Removes routing authority only; the application's RPC Peer remains unchanged. */
    public void unregisterRouter(int id) { engine.unregisterRouter(id); }
    /** Discovery-owned offline notification, scoped to the exact service incarnation. */
    public void removeNode(int nodeId, long nodeEpoch) { engine.removeNode(nodeId, nodeEpoch); }
    public boolean isRouterReady(int id) { return engine.isRouterReady(id); }
    public Map<BindingKey, RouteBinding> localBindings() { return engine.localBindings(); }
    public Optional<RouteBinding> resolve(int serviceId, long key) { return engine.resolve(serviceId, key); }
    public Set<Integer> serviceNodes(int serviceId) { return engine.serviceNodes(serviceId); }
}
