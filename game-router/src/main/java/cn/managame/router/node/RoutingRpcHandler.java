package cn.managame.router.node;

import cn.managame.rpc.call.*;
import cn.managame.rpc.message.*;
import java.util.function.Predicate;

/** Private composition policy: routing traffic goes to routing, ordinary traffic to the application. */
final class RoutingRpcHandler implements RpcHandler {
    private final RpcHandler application, routing;
    private final Predicate<RpcCallback<?>> routingCallback;
    RoutingRpcHandler(RpcHandler application, RpcHandler routing, Predicate<RpcCallback<?>> routingCallback) {
        this.application = application; this.routing = routing; this.routingCallback = routingCallback;
    }
    public void onRequest(int source, int slot, RpcRequest request) {
        (request.command() == RouterWire.COMMAND ? routing : application).onRequest(source, slot, request);
    }
    public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
        (routingCallback.test(callback) ? routing : application).onResponse(source, command, response, callback);
    }
    public void onFail(int target, int command, int error, RpcCallback<?> callback) {
        (routingCallback.test(callback) ? routing : application).onFail(target, command, error, callback);
    }
}
