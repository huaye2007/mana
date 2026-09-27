package cn.managame.network.netty;

import cn.managame.network.connection.Connection;
import cn.managame.network.connector.ConnectCallback;
import cn.managame.network.error.NetworkException;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class ConnectAttempt implements ConnectionEstablishment {
    private final AtomicBoolean completed = new AtomicBoolean();
    private final CountDownLatch latch = new CountDownLatch(1);
    private final ConnectCallback callback;
    private Consumer<ConnectAttempt> completion;
    private final EventLoop loop;
    private volatile Channel channel;
    private volatile Connection connection;
    private volatile Throwable cause;
    private volatile boolean cancelled;

    ConnectAttempt(EventLoop loop, ConnectCallback callback, Consumer<ConnectAttempt> completion) {
        this.loop = loop;
        this.callback = callback;
        this.completion = completion;
    }
    void attach(Channel channel) {
        this.channel = channel;
        if (cancelled) channel.close();
    }
    // Claim before onConnected: cancellation cannot win after the connection is handed to the handler.
    @Override public boolean claimSuccess() { return completed.compareAndSet(false, true); }
    @Override public void success(Connection connection) {
        this.connection = connection;
        complete();
        if (callback != null) dispatch(() -> callback.onSuccess(connection));
    }
    void fail(Throwable cause) {
        if (!completed.compareAndSet(false, true)) return;
        this.cause = cause;
        cancelled = true;
        Channel current = channel;
        if (current != null) current.close();
        complete();
        if (callback != null) dispatch(() -> callback.onFailure(cause));
    }
    @Override public void networkFailure(Throwable cause) {
        fail(cause instanceof NetworkException ? cause : new NetworkException("Transport establishment failed", cause));
    }
    private void complete() {
        Consumer<ConnectAttempt> listener = completion;
        completion = null;
        listener.accept(this);
        latch.countDown();
    }

    private void dispatch(Runnable action) {
        Runnable safe = () -> {
            try { action.run(); }
            catch (Throwable error) { NetworkSupport.log("ConnectCallback failed", error); }
        };
        if (loop.inEventLoop()) safe.run();
        else {
            try { loop.execute(safe); }
            catch (RejectedExecutionException rejected) {
                // An externally terminated executor cannot deliver; still guarantee one final result.
                safe.run();
            }
        }
    }
    Connection await() {
        try { latch.await(); }
        catch (InterruptedException interrupted) {
            cancelled = true;
            fail(new NetworkException("Connect interrupted", interrupted));
            // Success may have just won its CAS: close its channel as the caller cannot receive it.
            Channel current = channel;
            if (current != null) current.close();
            Thread.currentThread().interrupt();
            throw new NetworkException("Connect interrupted", interrupted);
        }
        if (cause instanceof RuntimeException runtime) throw runtime;
        if (cause != null) throw new NetworkException("Connect failed", cause);
        return connection;
    }
}