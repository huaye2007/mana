package cn.managame.rpc.netty;

import cn.managame.network.netty.connection.NettyAccess;
import cn.managame.network.netty.transport.NetworkOptions;
import cn.managame.network.netty.transport.NetworkResources;
import cn.managame.network.netty.transport.TcpNetworkClient;
import cn.managame.network.netty.transport.TcpNetworkServer;
import cn.managame.rpc.transport.RpcNetworkConfig;
import cn.managame.rpc.transport.RpcTransport;

import cn.managame.network.*;
import cn.managame.network.netty.transport.*;
import cn.managame.rpc.core.*;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.util.concurrent.EventExecutor;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Internal TCP lifecycle and frame writes. Users create RpcNode, not this adapter. */
public final class NettyRpcTransport implements RpcTransport {
    private final RpcNetworkConfig config;
    private Listener listener;
    private final NetworkResources shared;
    private final Set<EventExecutor> eventLoops = ConcurrentHashMap.newKeySet();
    private TcpNetworkClient client;
    private TcpNetworkServer server;
    private NetworkResources resources;
    private volatile boolean closed;

    NettyRpcTransport(RpcNetworkConfig config, NetworkResources shared) {
        this.config = config;
        this.shared = shared;
    }

    public ByteBufAllocator allocator() {
        return PooledByteBufAllocator.DEFAULT;
    }

    public void start(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        checkLifecycleThread();
        if (closed || this.listener != null)
            throw new IllegalStateException("Transport already started or closed");
        this.listener = listener;
        resources = shared == null ? NetworkResources.builder().ioThreads(2).build() : shared;
        client =
                TcpNetworkClient.builder()
                        .resources(resources)
                        .pipeline(this::pipeline)
                        .handlerFactory(listener::newOutboundHandler)
                        .build();
        server =
                TcpNetworkServer.builder()
                        .resources(resources)
                        .option(NetworkOptions.READ_IDLE, config.readIdleTimeout())
                        .listen(
                                config.listenAddress().getHostString(),
                                config.listenAddress().getPort())
                        .pipeline(this::pipeline)
                        .handlerFactory(listener::newInboundHandler)
                        .build();
        client.init();
        server.start();
    }

    private void pipeline(Connection connection, ChannelPipeline pipeline) {
        eventLoops.add(pipeline.channel().eventLoop());
        configurePipeline(pipeline, config.maxMessageBytes(), config.consolidateFlush());
    }

    static void configurePipeline(
            ChannelPipeline pipeline, int maxFrameBytes, boolean consolidate) {
        if (consolidate) pipeline.addLast("rpc.flush", new FlushConsolidationHandler(256, true));
        pipeline.addLast("rpc.frame", new NettyRpcFrameCodec(maxFrameBytes));
    }

    public void connect(InetSocketAddress address, ConnectCallback callback) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(callback, "callback");
        if (closed || client == null) {
            callback.onFailure(new IllegalStateException("Transport is not running"));
            return;
        }
        client.connect(address.getHostString(), address.getPort(), callback);
    }

    public InetSocketAddress localAddress() {
        return server == null
                ? null
                : server.boundAddresses().values().stream().findFirst().orElse(null);
    }

    public Submission write(Connection connection, ByteBuf frame) {
        Channel channel = NettyAccess.channel(connection);
        if (closed || !channel.isActive()) return Submission.UNAVAILABLE;
        int size = frame.readableBytes();
        if (!channel.isWritable() || !NettyRpcFrameCodec.accepts(size, config.maxMessageBytes()))
            return Submission.OVERLOADED;
        ByteBuf output = frame.retainedDuplicate();
        var promise = channel.newPromise();
        promise.addListener(
                result -> {
                    if (!result.isSuccess()) {
                        // Failure reporting must not prevent physical connection cleanup.
                        try {
                            listener.onWriteFailure(connection, result.cause());
                        } finally {
                            connection.close();
                        }
                    }
                });
        try {
            channel.writeAndFlush(output, promise);
        } catch (RuntimeException e) {
            promise.tryFailure(e);
        }
        return Submission.ACCEPTED;
    }

    public void checkLifecycleThread() {
        for (var loop : eventLoops)
            if (loop.inEventLoop())
                throw new IllegalStateException(
                        "Synchronous RPC lifecycle cannot run on a network EventLoop");
    }

    public void close() {
        checkLifecycleThread();
        closed = true;
        RuntimeException failure = null;
        try {
            if (server != null) server.stop();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            if (client != null) client.destroy();
        } catch (RuntimeException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        try {
            if (shared == null && resources != null) resources.close();
        } catch (RuntimeException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }
}
