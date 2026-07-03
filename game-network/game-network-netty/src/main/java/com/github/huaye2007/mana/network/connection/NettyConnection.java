package com.github.huaye2007.mana.network.connection;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.net.InetSocketAddress;

public class NettyConnection implements IConnection {

    protected final Channel channel;
    protected final long connectionId;

    public NettyConnection(long connectionId,Channel channel){
        this.connectionId = connectionId;
        this.channel = channel;
    }

    @Override
    public long getConnectionId() {
        return connectionId;
    }

    @Override
    public ConnectionType getType() {
        return ConnectionType.TCP;
    }


    @Override
    public String getRemoteAddress() {
        if (channel.remoteAddress() instanceof InetSocketAddress addr) {
            return addr.getHostString() + ":" + addr.getPort();
        }
        return channel.remoteAddress() != null ? channel.remoteAddress().toString() : "unknown";
    }

    @Override
    public void writeMsg(Object packet) {
        channel.writeAndFlush(packet);
    }

    @Override
    public void writeMsg(Object packet, IWriteCallback writeCallback) {
        if(writeCallback == null){
            writeMsg(packet);
            return;
        }
        ChannelFuture channelFuture = channel.writeAndFlush(packet);
        channelFuture.addListener(writeFuture ->{
            if(writeFuture.isSuccess()){
                writeCallback.onSuccess();
            }
            else{
                writeCallback.onFailure(writeFuture.cause());
            }
        });
    }

    @Override
    public void close() {
        channel.close();
    }


    public Channel getChannel(){
        return channel;
    }

    @Override
    public boolean isActive() {
        return channel.isActive();
    }

    /**
     * 出站缓冲是否低于高水位。false 表示对端消费慢、缓冲堆积，调用方应 fail-fast 而不是继续写。
     */
    public boolean isWritable() {
        return channel.isWritable();
    }
}
