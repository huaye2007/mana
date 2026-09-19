package cn.managame.network.netty.connection;

import cn.managame.network.AttributeKey;
import cn.managame.network.Connection;
import cn.managame.network.ConnectionType;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.util.ReferenceCountUtil;
import java.util.function.Consumer;
import io.netty.util.Attribute;

import java.net.SocketAddress;
import java.util.Objects;

/** Netty channel adapter with bounded admission before EventLoop submission. */
public final class NettyConnection implements Connection {
    final Channel channel;
    private final ConnectionType type;
    private final OutboundWriteLimits limits;
    private final io.netty.channel.MessageSizeEstimator.Handle messageSizer;
    private int pendingWrites;
    private long pendingBytes;
    private static final Consumer<Throwable> NO_COMPLETION = failure -> { };

    public NettyConnection(Channel channel, ConnectionType type) {
        this(channel, type, OutboundWriteLimits.DEFAULT);
    }

    public NettyConnection(Channel channel, ConnectionType type, OutboundWriteLimits limits) {
        this.channel = Objects.requireNonNull(channel);
        this.type = Objects.requireNonNull(type);
        this.limits = Objects.requireNonNull(limits);
        this.messageSizer = channel.config().getMessageSizeEstimator().newHandle();
    }

    @Override
    public ConnectionType type() {
        return type;
    }

    @Override
    public String id() {
        return channel.id().asLongText();
    }

    @Override
    public boolean isActive() {
        return channel.isActive();
    }

    @Override
    public synchronized boolean isWritable() {
        return channel.isActive() && channel.isWritable()
                && pendingWrites < limits.maxPendingWrites() && pendingBytes < limits.maxPendingBytes();
    }

    @Override
    public SocketAddress remoteAddress() {
        return channel.remoteAddress();
    }

    @Override
    public boolean write(Object message) { return write(message, NO_COMPLETION); }

    @Override
    public boolean write(Object message, Consumer<? super Throwable> completion) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(completion, "completion");
        long bytes;
        synchronized (this) {
            bytes = message instanceof ByteBuf buffer ? buffer.readableBytes()
                    : message instanceof ByteBufHolder holder ? holder.content().readableBytes()
                    : Math.max(0, messageSizer.size(message));
            if (!channel.isActive() || pendingWrites >= limits.maxPendingWrites()
                    || bytes > limits.maxPendingBytes() - pendingBytes) return false;
            pendingWrites++;
            pendingBytes += bytes;
        }
        ChannelPromise promise;
        try {
            promise = channel.newPromise();
        } catch (Throwable failure) {
            ReferenceCountUtil.safeRelease(message);
            completeWrite(bytes, completion, failure);
            return true;
        }
        promise.addListener(result -> completeWrite(bytes, completion, result.isSuccess() ? null : result.cause()));
        try {
            channel.writeAndFlush(message, promise);
        } catch (Throwable failure) {
            // Native Netty reports write/rejection failures through the promise and releases the message.
            // Cover a channel implementation that throws before accepting ownership.
            if (promise.tryFailure(failure)) ReferenceCountUtil.safeRelease(message);
        }
        return true;
    }

    private void completeWrite(long bytes, Consumer<? super Throwable> completion, Throwable failure) {
        synchronized (this) {
            pendingWrites--;
            pendingBytes -= bytes;
        }
        try { completion.accept(failure); }
        catch (Throwable error) {
            System.getLogger(NettyConnection.class.getName()).log(System.Logger.Level.WARNING,
                    "Write completion callback failed", error);
        }
    }

    @Override
    public void close() {
        channel.close();
    }

    @Override
    public <T> T get(AttributeKey<T> key) {
        return key.cast(attribute(key).get());
    }

    @Override
    public <T> void set(AttributeKey<T> key, T value) {
        attribute(key).set(key.cast(value));
    }

    @Override
    public <T> T remove(AttributeKey<T> key) {
        return key.cast(attribute(key).getAndSet(null));
    }

    @Override
    public <T> boolean compareAndSet(AttributeKey<T> key, T expected, T update) {
        return attribute(key).compareAndSet(expected, key.cast(update));
    }

    private <T> Attribute<T> attribute(AttributeKey<T> key) {
        return channel.attr(io.netty.util.AttributeKey.valueOf(key.id()));
    }
}
