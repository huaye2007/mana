package cn.managame.rpc;

import cn.managame.rpc.connection.RpcConnection;
import cn.managame.rpc.protocol.RpcCodec;
import cn.managame.serialization.SerializerManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.HashedWheelTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 对内 RPC 服务端：监听端口，每条入站连接装配 编解码 + 握手/心跳 + 业务 handler，
 * 既能处理客户端请求（{@link RpcMessageHandler#handleUserMsg}），也能在握手后反向 invoke 客户端。
 * boss/worker 线程组与时间轮均由构造方法内部创建，并在 {@link #close()} 释放。
 */
public class RpcServer extends RpcContainer {

    private static final Logger log = LoggerFactory.getLogger(RpcServer.class);

    private final RpcServerConfig config;
    private final MultiThreadIoEventLoopGroup bossGroup; // 入站独占（accept）
    private Channel serverChannel;

    public RpcServer(RpcServerConfig config, RpcMessageHandler handler) {
        config.validate();
        if (handler == null) {
            throw new GameRpcException("handler is required");
        }
        this.config = config;
        this.serializerManager = SerializerManager.getInstance();
        this.handler = handler;
        this.defaultTimeoutMillis = config.getDefaultTimeoutMillis();
        this.maxPendingPerPeer = config.getMaxPendingPerPeer();
        this.reclaimEmptyPeers = true; // 服务端：客户端永久下线后回收空 peer
        this.bossGroup = new MultiThreadIoEventLoopGroup(config.getBossThreads(), NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(config.getWorkerThreads(), NioIoHandler.newFactory());
        this.sharedTimer = new HashedWheelTimer();
        this.codec = new RpcCodec(serializerManager, config.getMaxFrameLength());
        handler.bind(this);
    }

    public void start() {
        RpcServerInternalHandler internalHandler = new RpcServerInternalHandler(this);
        int idleSeconds = config.getIdleTimeoutSeconds();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, config.getBacklog())
                .childOption(ChannelOption.TCP_NODELAY, config.isTcpNoDelay())
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        codec.configure(channel.pipeline());
                        if (idleSeconds > 0) {
                            // 读空闲：超时内没收到客户端任何字节（含心跳 ping）则判死并关闭
                            channel.pipeline().addLast("rpcIdle",
                                    new IdleStateHandler(idleSeconds, 0, 0, TimeUnit.SECONDS));
                        }
                        channel.pipeline().addLast("rpcInternal", internalHandler);
                        channel.pipeline().addLast("rpcHandler", handler);
                    }
                });

        try {
            serverChannel = bootstrap.bind(config.getPort()).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownGroups();
            throw new GameRpcException("rpc server bind interrupted: port=" + config.getPort(), e);
        } catch (Throwable t) {
            // 绑定失败（端口占用等）：清理已建的 group/timer，避免线程泄漏
            shutdownGroups();
            throw new GameRpcException("rpc server bind failed: port=" + config.getPort(), t);
        }
        log.info("rpc server started, port={}", config.getPort());
    }

    @Override
    protected void onChannelActive(Channel channel) {
        // 服务端先登记一条尚未握手的连接，身份在握手后补齐；纯 client→server 调用也能在此连接上回包
        registerConnection(new RpcConnection(channel));
    }

    public String getAuthToken() {
        return config.getAuthToken();
    }

    public long getHandshakeTimeoutMillis() {
        return config.getHandshakeTimeoutMillis();
    }

    public int getPort() {
        return config.getPort();
    }

    public void close() {
        close(config.getShutdownGraceMillis());
    }

    /**
     * 优雅停机：先停止接受新连接、拒绝新的反向 invoke，再给在途请求处理与响应刷写一个最长 graceMillis 的窗口
     * （worker 以非零静默期优雅关闭：已在途的响应在窗口内刷完才真正终止），最后释放线程组。
     */
    public void close(long graceMillis) {
        beginShutdown();
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly(); // 停止接受新连接
        }
        long quiet = graceMillis <= 0 ? 0 : Math.min(Math.max(graceMillis / 10, 200), graceMillis);
        shutdownGroups(quiet, Math.max(graceMillis, quiet));
    }

    private void shutdownGroups() {
        shutdownGroups(0, 2000); // 立即关闭（bind 失败清理用）
    }

    private void shutdownGroups(long workerQuietMillis, long workerTimeoutMillis) {
        bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        workerGroup.shutdownGracefully(workerQuietMillis, workerTimeoutMillis, TimeUnit.MILLISECONDS)
                .syncUninterruptibly();
        sharedTimer.stop();
    }
}
