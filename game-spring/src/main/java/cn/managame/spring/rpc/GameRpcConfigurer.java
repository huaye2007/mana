package cn.managame.spring.rpc;

import cn.managame.rpc.call.RpcHandler;
import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.node.RpcNodeBuilder;

/**
 * Customizes the managed RpcNode. Hooks run in Spring order: configure, then decorate,
 * then attach after build but before start. detach runs in reverse order before the Node closes.
 */
@FunctionalInterface
public interface GameRpcConfigurer {
    /** Adjusts transport settings. The adapter replaces any handler set here. */
    void configure(RpcNodeBuilder builder);

    /**
     * Wraps the Node handler before build, for example with GameRouter.forRouterNode or
     * GameRouter.forServiceNode using {@code handler} as the direct handler. Must return nonnull.
     */
    default RpcHandler decorate(RpcHandler handler) { return handler; }

    /** Called once after build and before start, for example routing.start(node). */
    default void attach(RpcNode node) {}

    /** Called once before the managed Node closes, for example routing.close(). */
    default void detach(RpcNode node) {}
}
