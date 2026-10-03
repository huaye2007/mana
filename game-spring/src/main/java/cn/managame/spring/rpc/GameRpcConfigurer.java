package cn.managame.spring.rpc;

import cn.managame.rpc.node.RpcNodeBuilder;

@FunctionalInterface
public interface GameRpcConfigurer {
    void configure(RpcNodeBuilder builder);
}
