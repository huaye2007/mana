package cn.managame.rpc;

/** Loaded from game-rpc-netty by default. Explicit providers support borrowed network resources. */
public interface RpcNetworkProvider {
    RpcTransport create(RpcNetworkConfig config);
}
