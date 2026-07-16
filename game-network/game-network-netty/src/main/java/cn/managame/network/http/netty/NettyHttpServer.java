package cn.managame.network.http.netty;

import cn.managame.network.http.IHttpHandler;
import cn.managame.network.server.INetworkServer;
import cn.managame.network.server.NettyServer;

import java.net.SocketAddress;
import java.util.Objects;

public final class NettyHttpServer implements INetworkServer, AutoCloseable {

    private final NettyServer server;

    NettyHttpServer(NettyServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    public static NettyHttpServerBuilder builder(IHttpHandler handler) {
        return new NettyHttpServerBuilder(handler);
    }

    @Override
    public void start() {
        server.start();
    }

    @Override
    public void stop() {
        server.stop();
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return server.isRunning();
    }

    public SocketAddress localAddress() {
        return server.localAddress();
    }
}
