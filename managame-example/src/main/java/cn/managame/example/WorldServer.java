package cn.managame.example;

import static cn.managame.FrameworkErrorCodes.HANDLER_NOT_FOUND;

import cn.managame.example.proto.RankAnswer;
import cn.managame.example.proto.RankQuery;
import cn.managame.network.connection.Connection;
import cn.managame.rpc.RpcNode;
import cn.managame.rpc.RpcPeer;
import cn.managame.rpc.RpcRequest;
import cn.managame.rpc.RpcResponse;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.executor.RouteExecutors;
import java.util.HashMap;
import java.util.Map;

/**
 * A world server: game servers dial it over RPC and ask a player's rank by gold, in protobuf. It joins rpc and runtime
 * in its own code, as every application does: the RpcHandler, on the connection's EventLoop, reads the request and
 * hands the work to the runtime, whose one world route keeps the board without locks.
 */
final class WorldServer implements AutoCloseable {
    static final int NODE_ID = 1, RANK = 1;
    private static final int WORLD = 1;
    private static final GameContext BOARD = GameContext.of(WORLD, 0);
    private final GameRuntime runtime = GameRuntime.builder().domain(WORLD, RouteExecutors.striped(1), RouteKeys.constant(0)).build();
    /** Each player's gold as last reported; touched on the world route only. */
    private final Map<Long, Long> goldByPlayer = new HashMap<>();
    private final RpcNode node;

    WorldServer(int port) {
        node = RpcNode.builder().nodeId(NODE_ID).listen(port).handler(this::handle).start();
    }

    int port() { return node.port(); }

    private void handle(RpcPeer game, Connection connection, Object message) {
        if (!(message instanceof RpcRequest request)) return;
        if (request.command() != RANK) {
            RpcResponse.failure(request.requestId(), HANDLER_NOT_FOUND).writeTo(connection);
            return;
        }
        long playerId = request.routeKey(), gold = Protocol.read(RankQuery.parser(), request.body()).getGold();   // read now: the body is released after
        int requestId = request.requestId();
        runtime.execute(BOARD, _ -> {
            goldByPlayer.put(playerId, gold);
            long rank = 1 + goldByPlayer.values().stream().filter(other -> other > gold).count();
            RankAnswer answer = RankAnswer.newBuilder().setRank(rank).setRanked(goldByPlayer.size()).build();
            RpcResponse.of(requestId, Protocol.body(answer)).writeTo(connection);
        });
    }

    /** RPC first, so no request comes in, then the runtime, which finishes what it accepted. */
    @Override public void close() {
        node.close();
        runtime.close();
    }
}
