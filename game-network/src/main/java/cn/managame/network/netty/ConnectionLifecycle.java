package cn.managame.network.netty;

import cn.managame.network.connection.ConnectionHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.nio.channels.ClosedChannelException;

/** EventLoop-confined establishment and lifecycle coordination, with no protocol dependencies. */
final class ConnectionLifecycle extends ChannelInboundHandlerAdapter {
    private final Channel channel;
    private final ConnectionHandlerAdapter adapter;
    private ConnectionEstablishment establishment;
    private ChannelHandlerContext context;
    private int handshakes;
    private boolean initialized, active, connected, ended;

    ConnectionLifecycle(Channel channel, ConnectionHandler handler, ConnectionEstablishment establishment) {
        this.channel = channel;
        this.establishment = establishment;
        adapter = new ConnectionHandlerAdapter(handler, this::fail);
    }

    ConnectionHandlerAdapter adapter() { return adapter; }

    /** Each protocol owns its one-shot prerequisite; duplicate completion cannot release another. */
    Handshake expectHandshake() {
        if (initialized) throw new IllegalStateException("Handshake registered after initialization");
        handshakes++;
        return new Handshake();
    }

    final class Handshake {
        private boolean complete;
        void succeed() {
            if (complete || ended) return;
            complete = true;
            handshakes--;
            tryEstablish();
        }
    }

    void initialized() {
        initialized = true;
        tryEstablish();
    }

    @Override public void handlerAdded(ChannelHandlerContext ctx) { context = ctx; }

    @Override public void channelActive(ChannelHandlerContext ctx) {
        active = true;
        tryEstablish();
        ctx.fireChannelActive();
    }

    private void tryEstablish() {
        if (!initialized || !active || handshakes != 0 || connected || ended || !channel.isActive()) return;
        ConnectionEstablishment owner = establishment;
        if (!owner.claimSuccess()) {
            // The owner already decided cancellation/closure. Do not invent another failure.
            ended = true;
            establishment = null;
            owner.release();
            channel.close();
            return;
        }
        connected = true;
        establishment = null;
        owner.release();
        var connection = adapter.connected(channel);
        // onConnected may close synchronously; establishment remains successful.
        owner.success(connection);
    }

    void fail(Throwable cause) {
        if (connected || ended) return;
        ended = true;
        ConnectionEstablishment owner = establishment;
        establishment = null;
        try {
            owner.release();
            owner.networkFailure(cause);
        } finally {
            channel.close();
        }
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!connected || ended) {
            ReferenceCountUtil.release(message);
            if (!ended) fail(new IllegalStateException("Message arrived before transport establishment"));
        } else ctx.fireChannelRead(message);
    }

    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (connected && !ended) ctx.fireUserEventTriggered(event);
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        if (!connected && !ended) fail(new ClosedChannelException());
        ended = true;
        adapter.disconnected();
        ctx.fireChannelInactive();
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (connected) ctx.fireExceptionCaught(cause);
        else if (!ended) fail(cause);
        else NetworkSupport.log("Exception after establishment ended", cause);
    }

    ChannelInboundHandlerAdapter writeErrors() { return new WriteErrors(); }

    /** Void-promise failures bypass transport close policies but still traverse application handlers. */
    private final class WriteErrors extends ChannelInboundHandlerAdapter {
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (connected) context.fireExceptionCaught(cause);
            else ctx.fireExceptionCaught(cause);
        }
    }
}
