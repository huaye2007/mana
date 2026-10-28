package cn.managame.benchmark.rpc;

import cn.managame.benchmark.CountingSocketChannel;
import cn.managame.benchmark.PeerProcess;
import cn.managame.rpc.RpcNode;
import cn.managame.rpc.RpcNotify;
import cn.managame.rpc.RpcPeer;
import cn.managame.rpc.RpcRequest;
import cn.managame.rpc.RpcResponse;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two nodes over loopback, as a world server and a game server: the game server dials the world server, whose
 * handler echoes every request's body back on the EventLoop without copying it and counts every notify. The world
 * server runs in this process or, with {@code otherProcess}, in a {@link WorldPeer} of its own. Calls and notifies
 * send a duplicate of one payload, so the benchmarks measure the RPC path, not the making of bodies. The game
 * server's channels count the bytes on the wire.
 *
 * <p>{@link #notified} bounds the notifies in flight. A world server in this process releases it for every notify it
 * handles; one in its own process sends HANDLED notifies back instead, and answers FLUSH with the notifies it has
 * not acknowledged yet, as {@link WorldPeer} says.
 */
final class RpcFixture implements AutoCloseable {
    static final int ECHO = 1, NOTE = 2, HANDLED = 3, FLUSH = 4;
    private final int connections;
    private final RpcNode world;
    private final PeerProcess worldProcess;
    private final EventLoopGroup gameLoops = CountingSocketChannel.newGroup("rpc-game");
    final RpcNode game;
    final RpcPeer peer;
    final ByteBuf payload;
    final Semaphore notified = new Semaphore(0);

    RpcFixture(int payloadSize, int connections, boolean otherProcess) throws IOException, InterruptedException {
        this.connections = connections;
        world = otherProcess ? null : RpcNode.builder().nodeId(1).listen(new InetSocketAddress("127.0.0.1", 0))
                .handler((rpcPeer, connection, msg) -> {
                    switch (msg) {
                        case RpcRequest request -> RpcResponse.of(request.requestId(), request.body().retain()).writeTo(connection);
                        case RpcNotify notify -> notified.release();
                        default -> {}
                    }
                }).start();
        worldProcess = otherProcess ? PeerProcess.start(WorldPeer.class) : null;
        int port = otherProcess ? worldProcess.port() : world.port();
        game = RpcNode.builder().nodeId(2).bootstrap(new Bootstrap().group(gameLoops).channel(CountingSocketChannel.class))
                .handler((rpcPeer, connection, msg) -> {
                    if (msg instanceof RpcNotify handled && handled.command() == HANDLED) notified.release((int) handled.businessId());
                }).start();
        peer = game.createPeer(1, new InetSocketAddress("127.0.0.1", port), connections);
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

    /**
     * Waits until the world server has handled the notifies sent, all {@code window} permits back. One in its own
     * process is asked to FLUSH on every connection first, after the notifies sent on it.
     */
    void settleNotifies(int window) throws InterruptedException {
        if (worldProcess != null) {
            CountDownLatch flushed = new CountDownLatch(connections);
            AtomicInteger failed = new AtomicInteger();
            for (int i = 0; i < connections; i++) // route key i takes the i-th connection
                peer.call(RpcRequest.of(FLUSH, i, Unpooled.EMPTY_BUFFER), (request, response) -> {
                    if (response.isSuccess()) notified.release(response.body().getInt(response.body().readerIndex()));
                    else failed.incrementAndGet();
                    flushed.countDown();
                });
            flushed.await();
            if (failed.get() > 0) throw new IllegalStateException(failed.get() + " flushes failed");
        }
        notified.acquire(window);
        notified.release(window);
    }

    @Override public void close() {
        game.close();
        if (world != null) world.close();
        if (worldProcess != null) worldProcess.close();
        gameLoops.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        payload.release();
    }
}
