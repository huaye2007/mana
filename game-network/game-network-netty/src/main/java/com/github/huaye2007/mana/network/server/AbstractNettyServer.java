package com.github.huaye2007.mana.network.server;

import com.github.huaye2007.mana.network.connection.ConnectionManager;
import com.github.huaye2007.mana.network.handler.pipeline.IPipelineConfigurator;
import com.github.huaye2007.mana.network.session.SessionManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public abstract class AbstractNettyServer implements INetworkServer {

    protected final NetworkServerConfig config;
    protected final ConnectionManager connectionManager;
    protected final SessionManager sessionManager;
    protected final IPipelineConfigurator pipelineConfigurator;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    protected AbstractNettyServer(NetworkServerConfig config, IPipelineConfigurator pipelineConfigurator,
                                  SessionManager sessionManager, ConnectionManager connectionManager) {
        this.config = config;
        this.pipelineConfigurator = pipelineConfigurator;
        this.sessionManager = sessionManager;
        this.connectionManager = connectionManager;
    }

    @Override
    public void start() {
        try {
            validateConfig();
            bossGroup = newEventLoopGroup(config.getBossThreads());
            workerGroup = newEventLoopGroup(config.getWorkerThreads());
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, config.getSoBacklog())
                    .childOption(ChannelOption.TCP_NODELAY, config.isTcpNoDelay())
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            pipelineConfigurator.configure(channel.pipeline());
                        }
                    });

            bootstrap.bind(config.getHost(), config.getPort()).sync();
        } catch (Exception e) {
            shutdownEventLoopGroups();
            throw new RuntimeException(e);
        }
    }

    @Override
    public void stop() {
        try {
            connectionManager.closeAll();
            if (sessionManager != null) {
                sessionManager.closeAll();
            }
        } finally {
            shutdownEventLoopGroups();
        }
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    protected void validateConfig() {
    }

    private EventLoopGroup newEventLoopGroup(int threads) {
        return new MultiThreadIoEventLoopGroup(threads, NioIoHandler.newFactory());
    }

    private void shutdownEventLoopGroups() {
        if(workerGroup != null){
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
        if(bossGroup != null){
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
    }
}
