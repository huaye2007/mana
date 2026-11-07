package cn.managame.example.rpc;

import cn.managame.example.NodeConstant;
import cn.managame.example.Protocols;
import cn.managame.rpc.RpcNode;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.dispatch.Dispatcher;
import java.net.InetSocketAddress;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RPC 的装配。每个进程是一个节点，用 -Drpc.node 指定 id（默认世界服）；要被别的节点连接时用 -Drpc.port 指定监听端口；要
 * 主动连别的节点时用 -Drpc.peers 列出它们，比如一个世界服、一个游戏服：
 *
 * <pre>
 * 世界服：-Drpc.node=1 -Drpc.port=7101 -Dgame.port=7001
 * 游戏服：-Drpc.node=2 -Drpc.peers=1=127.0.0.1:7101 -Dgame.port=7002
 * </pre>
 */
@Configuration
public class RpcConfig {
    private static final int NODE = Integer.getInteger("rpc.node", NodeConstant.WORLD);
    private static final Integer PORT = Integer.getInteger("rpc.port");
    /** 逗号分隔的 节点id=主机:端口。 */
    private static final String PEERS = System.getProperty("rpc.peers", "");

    @Bean
    public GameRpc gameRpc(GameRuntime gameRuntime, Protocols protocols) { return new GameRpc(gameRuntime, protocols); }

    /** 依赖 dispatcher：Spring 先关节点，再关 GameRuntime，业务任务还在执行时 RPC 已经不再收消息。 */
    @Bean
    public RpcNode rpcNode(Dispatcher dispatcher, Protocols protocols, GameRpc gameRpc) {
        RpcNode.Builder builder = RpcNode.builder().nodeId(NODE).handler(new RpcGateway(dispatcher, protocols));
        if (PORT != null) builder.listen(PORT);
        RpcNode node = builder.start();
        for (String peer : PEERS.split(",")) {
            if (peer.isBlank()) continue;
            String[] parts = peer.trim().split("[=:]");
            // 不提前解析主机名，每次重连都重新解析，对端换了 IP 也能连上
            node.createPeer(Integer.parseInt(parts[0]), InetSocketAddress.createUnresolved(parts[1], Integer.parseInt(parts[2])));
        }
        gameRpc.node(node);
        return node;
    }
}
