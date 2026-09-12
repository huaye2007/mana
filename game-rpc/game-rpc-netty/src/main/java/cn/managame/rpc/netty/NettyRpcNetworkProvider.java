package cn.managame.rpc.netty;

import cn.managame.network.netty.NetworkResources;
import cn.managame.rpc.*;

/** Default service provider. An explicitly supplied NetworkResources remains externally owned. */
public final class NettyRpcNetworkProvider implements RpcNetworkProvider {
    private final NetworkResources shared;

    public NettyRpcNetworkProvider() {
        shared = null;
    }

    public NettyRpcNetworkProvider(NetworkResources shared) {
        this.shared = java.util.Objects.requireNonNull(shared);
    }

    public RpcTransport create(RpcNetworkConfig config) {
        return new NettyRpcTransport(config, shared);
    }
}
