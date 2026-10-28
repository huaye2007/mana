package cn.managame.benchmark.rpc;

import cn.managame.Metadata;
import cn.managame.benchmark.PeerProcess;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import cn.managame.rpc.RpcNode;
import cn.managame.rpc.RpcNotify;
import cn.managame.rpc.RpcPeer;
import cn.managame.rpc.RpcRequest;
import cn.managame.rpc.RpcResponse;
import io.netty.buffer.Unpooled;
import io.netty.util.AttributeKey;
import java.net.InetSocketAddress;

/**
 * The world server of {@link RpcFixture} in a process of its own, for the benchmarks that run their two sides apart.
 * It echoes requests as the fixture's does. Unable to release the game server's semaphore, it acknowledges notifies
 * itself: on each connection, a HANDLED notify back once {@link #BATCH} have come, with their number as its
 * businessId, and, asked to FLUSH, the number not acknowledged yet as the answer's body.
 */
public final class WorldPeer {
    static final int BATCH = 64;
    /** The notifies handled on a connection and not acknowledged yet; touched on its EventLoop only. */
    private static final AttributeKey<int[]> UNACKNOWLEDGED = AttributeKey.valueOf("cn.managame.benchmark.unacknowledged");

    private WorldPeer() {}

    public static void main(String[] args) throws Exception {
        RpcNode world = RpcNode.builder().nodeId(1).listen(new InetSocketAddress("127.0.0.1", 0)).handler(WorldPeer::handle).start();
        PeerProcess.serve(world, world.port());
        System.exit(0);
    }

    private static void handle(RpcPeer game, Connection connection, Object msg) {
        switch (msg) {
            case RpcRequest request when request.command() == RpcFixture.FLUSH -> {
                int[] count = unacknowledged(connection);
                RpcResponse.of(request.requestId(), Unpooled.copyInt(count[0])).writeTo(connection);
                count[0] = 0;
            }
            case RpcRequest request -> RpcResponse.of(request.requestId(), request.body().retain()).writeTo(connection);
            case RpcNotify notify -> {
                int[] count = unacknowledged(connection);
                if (++count[0] >= BATCH && game.notify(new RpcNotify(RpcFixture.HANDLED, 0, (byte) 0, count[0],
                        Metadata.empty(), Unpooled.EMPTY_BUFFER)) == WriteStatus.ACCEPTED) count[0] = 0;
            }
            default -> {}
        }
    }

    private static int[] unacknowledged(Connection connection) {
        int[] count = connection.get(UNACKNOWLEDGED);
        if (count == null) connection.set(UNACKNOWLEDGED, count = new int[1]);
        return count;
    }
}
