package cn.managame.benchmark.rpc;

import cn.managame.benchmark.CountingSocketChannel;
import cn.managame.rpc.RpcHandler;
import cn.managame.rpc.RpcNode;
import cn.managame.rpc.RpcNotify;
import cn.managame.rpc.RpcPeer;
import cn.managame.rpc.RpcRequest;
import cn.managame.rpc.RpcResponse;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import java.net.InetSocketAddress;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Two nodes in one process over loopback, as a world server and a game server: the game server dials the world
 * server, whose handler echoes every request's body back on the EventLoop without copying it and counts every
 * notify. Calls and notifies send a duplicate of one payload, so the benchmarks measure the RPC path, not the
 * making of bodies. The game server's channels count the bytes on the wire.
 */
final class RpcFixture implements AutoCloseable {
    static final int ECHO = 1, NOTE = 2;
    final RpcNode world, game;
    final RpcPeer peer;
    final ByteBuf payload;
    private final EventLoopGroup gameLoops = CountingSocketChannel.newGroup("rpc-game");
    /** Released by the world server for every notify it handles, to bound the notifies in flight. */
    final Semaphore notified = new Semaphore(0);

    RpcFixture(int payloadSize, int connections) throws InterruptedException {
        RpcHandler echo = (rpcPeer, connection, msg) -> {
            switch (msg) {
                case RpcRequest request -> RpcResponse.of(request.requestId(), request.body().retain()).writeTo(connection);
                case RpcNotify notify -> notified.release();
                default -> {}
            }
        };
        world = RpcNode.builder().nodeId(1).listen(new InetSocketAddress("127.0.0.1", 0)).handler(echo).start();
        game = RpcNode.builder().nodeId(2).bootstrap(new Bootstrap().group(gameLoops).channel(CountingSocketChannel.class)).start();
        peer = game.createPeer(1, new InetSocketAddress("127.0.0.1", world.port()), connections);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (peer.connectionCount() < connections) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("The nodes did not connect");
            Thread.sleep(10);
        }
        payload = Unpooled.directBuffer(payloadSize).writeZero(payloadSize);
    }

    /** A request with its own duplicate of the payload, keyed so that callers spread over the connections. */
    RpcRequest request(long routeKey) { return RpcRequest.of(ECHO, routeKey, payload.retainedDuplicate()); }

    RpcNotify notify(long routeKey) { return RpcNotify.of(NOTE, routeKey, payload.retainedDuplicate()); }

    @Override public void close() {
        game.close();
        world.close();
        gameLoops.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        payload.release();
    }
}
