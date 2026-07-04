package cn.managame.rpc.connection;

import io.netty.channel.Channel;

public class ClientRpcConnection extends RpcConnection{
    private String ip;
    private int port;

    public ClientRpcConnection(long connectionId, Channel channel) {
        super(connectionId, channel);
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
