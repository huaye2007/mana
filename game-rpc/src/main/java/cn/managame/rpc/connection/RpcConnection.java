package cn.managame.rpc.connection;

import cn.managame.network.connection.NettyConnection;
import io.netty.channel.Channel;


public class RpcConnection extends NettyConnection {

    // 服务端握手时由 IO 线程写、业务线程读，volatile 保证可见性
    private volatile String serviceName;

    private volatile String serviceId;

    private int index;

    public RpcConnection(long connectionId, Channel channel) {
        super(connectionId, channel);
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
