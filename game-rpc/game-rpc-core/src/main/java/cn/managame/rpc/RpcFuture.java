package cn.managame.rpc;

import cn.managame.common.context.MetadataKeys;
import cn.managame.serialization.ISerializer;
import cn.managame.serialization.SerializerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RPC 调用结果句柄（无 CompletableFuture）。两种取结果方式二选一：
 * 阻塞 {@link #await()} 拿原始 {@link RpcResponse}，或 {@link #callback(RpcCallback)} 设置单个回调
 * （按回调泛型自动反序列化 body）。回调在完成线程上执行——响应到达 / 写失败在 IO 线程、
 * 超时在时间轮线程，实现内禁止阻塞。
 */
public final class RpcFuture {

    private static final Logger log = LoggerFactory.getLogger(RpcFuture.class);

    private final long requestId;
    private final String connectionId;
    private final long startNanos;
    private final SerializerManager serializerManager; // 仅回调反序列化用，可为 null

    private final Object lock = new Object();
    private boolean done;
    private RpcResponse response;
    private Throwable cause;
    private RpcCallback<?> callback;
    private Runnable timeoutCancellation;

    RpcFuture(long requestId, String connectionId, long startNanos, SerializerManager serializerManager) {
        this.requestId = requestId;
        this.connectionId = connectionId;
        this.startNanos = startNanos;
        this.serializerManager = serializerManager;
    }

    /** 立即失败的 future（无连接 / 校验失败等同步错误）。 */
    static RpcFuture failed(Throwable cause) {
        RpcFuture future = new RpcFuture(0L, null, System.nanoTime(), null);
        future.completeExceptionally(cause);
        return future;
    }

    long getRequestId() {
        return requestId;
    }

    String getConnectionId() {
        return connectionId;
    }

    long getStartNanos() {
        return startNanos;
    }

    boolean complete(RpcResponse response) {
        RpcCallback<?> cb;
        synchronized (lock) {
            if (done) {
                return false;
            }
            this.response = response;
            this.done = true;
            cancelTimeout();
            cb = callback;
            lock.notifyAll();
        }
        if (cb != null) {
            dispatch(cb);
        }
        return true;
    }

    boolean completeExceptionally(Throwable cause) {
        RpcCallback<?> cb;
        synchronized (lock) {
            if (done) {
                return false;
            }
            this.cause = cause;
            this.done = true;
            cancelTimeout();
            cb = callback;
            lock.notifyAll();
        }
        if (cb != null) {
            safeOnException(cb, cause);
        }
        return true;
    }

    /** 绑定传输无关的超时取消动作；若已完成则立即执行。 */
    void setTimeoutCancellation(Runnable cancellation) {
        synchronized (lock) {
            if (done) {
                cancellation.run();
            } else {
                this.timeoutCancellation = cancellation;
            }
        }
    }

    private void cancelTimeout() {
        if (timeoutCancellation != null) {
            timeoutCancellation.run();
            timeoutCancellation = null;
        }
    }

    public boolean isDone() {
        synchronized (lock) {
            return done;
        }
    }

    public boolean isSuccess() {
        synchronized (lock) {
            return done && cause == null;
        }
    }

    public RpcResponse getResponse() {
        synchronized (lock) {
            return response;
        }
    }

    public Throwable cause() {
        synchronized (lock) {
            return cause;
        }
    }

    /** 阻塞直到完成（请求超时会保证最终完成）。成功返回响应，失败抛 {@link GameRpcException}。 */
    public RpcResponse await() throws InterruptedException {
        synchronized (lock) {
            while (!done) {
                lock.wait();
            }
            return resultLocked();
        }
    }

    /** 最多阻塞 timeoutMillis 毫秒；自身等待超时抛 {@link GameRpcException}。 */
    public RpcResponse await(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (lock) {
            while (!done) {
                long remainMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainMillis <= 0) {
                    throw new GameRpcException("rpc await timeout after " + timeoutMillis + "ms");
                }
                lock.wait(remainMillis);
            }
            return resultLocked();
        }
    }

    private RpcResponse resultLocked() {
        if (cause != null) {
            throw cause instanceof GameRpcException ge ? ge : new GameRpcException("rpc call failed", cause);
        }
        return response;
    }

    /** 设置完成回调（单个）；若已完成则在当前线程立即回调，重复设置抛 {@link IllegalStateException}。 */
    public RpcFuture callback(RpcCallback<?> callback) {
        boolean runNow;
        synchronized (lock) {
            if (this.callback != null) {
                throw new IllegalStateException("callback already set");
            }
            this.callback = callback;
            runNow = done;
        }
        if (runNow) {
            if (cause != null) {
                safeOnException(callback, cause);
            } else {
                dispatch(callback);
            }
        }
        return this;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatch(RpcCallback<?> callback) {
        RpcResponse resp = response;
        if (!resp.isSuccess()) {
            safeOnException(callback, new GameRpcException(resp.code(), "rpc error code=" + resp.code()
                    + describe(resp.metaString(MetadataKeys.RPC_ERROR_MESSAGE))));
            return;
        }
        Object value;
        try {
            value = decodeBody(resp, callback.getResponseType());
        } catch (Throwable t) {
            safeOnException(callback, t);
            return;
        }
        try {
            ((RpcCallback) callback).onSuccess(value);
        } catch (Throwable t) {
            log.warn("rpc callback onSuccess threw, requestId={}", requestId, t);
        }
    }

    private Object decodeBody(RpcResponse resp, Class<?> responseType) {
        byte[] bytes = resp.bodyAsBytes();
        if (responseType == null || responseType == byte[].class) {
            return bytes;
        }
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (serializerManager == null) {
            throw new GameRpcException("no serializerManager bound for response decode");
        }
        ISerializer serializer = serializerManager.getISerializer(resp.serialType());
        if (serializer == null) {
            throw new GameRpcException("no serializer for serialType=" + resp.serialType());
        }
        return serializer.deserialize(bytes, responseType);
    }

    private void safeOnException(RpcCallback<?> callback, Throwable error) {
        try {
            callback.onException(error);
        } catch (Throwable t) {
            log.warn("rpc callback onException threw, requestId={}", requestId, t);
        }
    }

    private static String describe(String message) {
        return message == null ? "" : ": " + message;
    }
}
