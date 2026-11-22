package cn.managame.example.rpc;

import cn.managame.example.NodeConstant;
import cn.managame.example.Protocols;
import cn.managame.rpc.RpcNode;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.dispatch.Dispatcher;
import java.net.InetSocketAddress;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * RPC 的装配。每个进程是一个节点，用 rpc.node 指定 id（默认世界服）；要被别的节点连接时用 rpc.port 指定监听端口；
 * 要主动连别的节点时用 rpc.peers 列出它们（逗号分隔的 节点id=主机:端口）。
 */
@Configuration
public class RpcConfig {

    @Bean
    public GameRpc gameRpc(GameRuntime gameRuntime, Protocols protocols) {
        return new GameRpc(gameRuntime, protocols);
    }

    /** 依赖 dispatcher：Spring 先关节点，再关 GameRuntime，业务任务还在执行时 RPC 已经不再收消息。 */
    @Bean
    public RpcNode rpcNode(Dispatcher dispatcher, Protocols protocols, GameRpc gameRpc, Environment env) {
        int nodeId = env.getProperty("rpc.node", Integer.class, NodeConstant.WORLD);
        Integer port = env.getProperty("rpc.port", Integer.class);
        String peers = env.getProperty("rpc.peers", "");

        RpcNode.Builder builder = RpcNode.builder().nodeId(nodeId).handler(new RpcGateway(dispatcher, protocols));
        if (port != null) builder.listen(port);
        RpcNode node = builder.start();
        for (String peer : peers.split(",")) {
            if (peer.isBlank()) continue;
            String[] parts = peer.trim().split("[=:]");
            // 不提前解析主机名，每次重连都重新解析，对端换了 IP 也能连上
            node.createPeer(Integer.parseInt(parts[0]), InetSocketAddress.createUnresolved(parts[1], Integer.parseInt(parts[2])));
        }
        gameRpc.node(node);
        return node;
    }
}
