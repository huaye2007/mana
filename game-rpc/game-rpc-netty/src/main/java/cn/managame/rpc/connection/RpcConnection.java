package cn.managame.rpc.connection;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * RPC 自有的 Netty 连接适配，不依赖 game-network，避免两个独立组件形成实现层耦合。
 */
public class RpcConnection {

    protected final Channel channel;
    private final String connectionId;

    // 服务端握手时由 IO 线程写、业务线程读，volatile 保证可见性
    private volatile String serviceName;
    private volatile String serviceId;
    private int index;

    public RpcConnection(Channel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.connectionId = channel.id().asLongText();
    }

    public String getConnectionId() {
        return connectionId;
    }

    public String getRemoteAddress() {
        if (channel.remoteAddress() instanceof InetSocketAddress address) {
            return address.getHostString() + ":" + address.getPort();
        }
        return channel.remoteAddress() != null ? channel.remoteAddress().toString() : "unknown";
    }

    public void writeMsg(Object packet) {
        channel.writeAndFlush(packet);
    }

    public void writeMsg(Object packet, RpcWriteCallback callback) {
        if (callback == null) {
            writeMsg(packet);
            return;
        }
        ChannelFuture future = channel.writeAndFlush(packet);
        future.addListener(writeFuture -> {
            if (writeFuture.isSuccess()) {
                callback.onSuccess();
            } else {
                callback.onFailure(writeFuture.cause());
            }
        });
    }

    public void close() {
        channel.close();
    }

    public Channel getChannel() {
        return channel;
    }

    public boolean isActive() {
        return channel.isActive();
    }

    public boolean isWritable() {
        return channel.isWritable();
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }
}
