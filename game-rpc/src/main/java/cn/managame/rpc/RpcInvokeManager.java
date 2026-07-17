package cn.managame.rpc;

import cn.managame.network.connection.IWriteCallback;
import cn.managame.rpc.connection.RpcConnection;
import io.netty.util.Timeout;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个 {@link RpcPeer} 的在途调用管理：分配 requestId、挂时间轮超时、按 requestId 关联响应。
 * requestId 在本 peer 内唯一（peer 的多条连接共享同一计数与映射），响应路由由 channel→连接→peer
 * 定位到本管理器后按 requestId 命中。
 */
public class RpcInvokeManager {

    private final RpcContainer container;
    private final AtomicLong requestIdGen = new AtomicLong();
    private final Map<Long, RpcFuture> rpcFutureMap = new ConcurrentHashMap<>();

    public RpcInvokeManager(RpcContainer container) {
        this.container = container;
    }

    public void oneway(RpcConnection connection, RpcRequest request) {
        tryOneway(connection, request);
    }

    /**
     * Attempts to enqueue a fire-and-forget request.
     *
     * @return {@code true} when the request was accepted by the channel, or
     *         {@code false} when local backpressure rejected it
     */
    public boolean tryOneway(RpcConnection connection, RpcRequest request) {
        RpcMetrics metrics = container.getMetrics();
        if (!connection.isWritable()) {
            // 出站缓冲堆到高水位，oneway 直接丢（fire-and-forget），不堆爆堆外内存
            metrics.onRejectedNotWritable();
            return false;
        }
        // fire-and-forget：不挂写回调（写失败也不重试），避免热路径每次分配 IWriteCallback + listener
        connection.writeMsg(request);
        metrics.onOnewaySent();
        return true;
    }

    public RpcFuture invoke(RpcConnection connection, RpcRequest request) {
        RpcMetrics metrics = container.getMetrics();
        if (container.isShuttingDown()) {
            // 优雅停机中：不再受理新请求，已在途的继续等响应
            return RpcFuture.failed(new GameRpcException(
                    "endpoint is shutting down, command=" + request.getCommand()));
        }
        if (!connection.isWritable()) {
            // 出站缓冲已堆积到高水位，对端消费不过来，fail-fast 而不是继续灌
            metrics.onRejectedNotWritable();
            return RpcFuture.failed(new GameRpcException(
                    "connection not writable (backpressure), command=" + request.getCommand()));
        }
        int maxPending = container.getMaxPendingPerPeer();
        // 这是防失控堆积的软上限，不承担业务一致性；并发下允许少量越界，避免热路径加锁。
        if (maxPending > 0 && rpcFutureMap.size() >= maxPending) {
            metrics.onRejectedPendingLimit();
            return RpcFuture.failed(new GameRpcException(
                    "pending requests exceed soft limit " + maxPending + ", command=" + request.getCommand()));
        }
        long requestId = requestIdGen.incrementAndGet();
        request.requestId(requestId);
        RpcFuture future = new RpcFuture(requestId, connection.getConnectionId(), System.nanoTime(),
                container.getSerializerManager());
        rpcFutureMap.put(requestId, future);

        long timeoutMillis = request.getTimeoutMillis() > 0
                ? request.getTimeoutMillis() : container.getDefaultTimeoutMillis();
        final Timeout timeout;
        try {
            timeout = container.getTimer().newTimeout(t -> {
                RpcFuture pending = removePending(requestId);
                if (pending != null && pending.completeExceptionally(new GameRpcException(
                        "rpc timeout after " + timeoutMillis + "ms, command=" + request.getCommand()))) {
                    metrics.onTimeout();
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable timerFailure) {
            RpcFuture pending = removePending(requestId);
            if (pending != null) {
                pending.completeExceptionally(new GameRpcException(
                        "rpc timeout registration failed, command=" + request.getCommand(), timerFailure));
            }
            return future;
        }
        future.setTimeout(timeout);

        try {
            connection.writeMsg(request, new IWriteCallback() {
                @Override
                public void onSuccess() {
                    metrics.onRequestSent();
                }

                @Override
                public void onFailure(Throwable cause) {
                    RpcFuture pending = removePending(requestId);
                    if (pending != null && pending.completeExceptionally(
                            new GameRpcException("rpc write failed, command=" + request.getCommand(), cause))) {
                        metrics.onWriteFailure();
                    }
                }
            });
        } catch (Throwable writeFailure) {
            RpcFuture pending = removePending(requestId);
            if (pending != null && pending.completeExceptionally(
                    new GameRpcException("rpc write failed, command=" + request.getCommand(), writeFailure))) {
                metrics.onWriteFailure();
            }
        }
        return future;
    }

    public <V> void invoke(RpcConnection connection, RpcRequest request, RpcCallback<V> callback) {
        invoke(connection, request).callback(callback);
    }

    /** 响应到达（IO 线程）：按 requestId 关联并完成 future。 */
    public void complete(RpcResponse response) {
        RpcMetrics metrics = container.getMetrics();
        RpcFuture future = removePending(response.requestId());
        if (future == null) {
            // 超时后才到 / 重复响应 / 未知 requestId
            metrics.onLateResponse();
            return;
        }
        if (response.isSuccess()) {
            metrics.onResponseOk();
        } else {
            metrics.onResponseError();
        }
        metrics.onResponseLatency(System.nanoTime() - future.getStartNanos());
        future.complete(response);
    }

    /** 连接断开时，快速失败该连接上所有在途调用，而不是等各自超时。 */
    public void failConnection(String connectionId, Throwable cause) {
        for (Map.Entry<Long, RpcFuture> entry : rpcFutureMap.entrySet()) {
            if (Objects.equals(entry.getValue().getConnectionId(), connectionId)) {
                if (removePending(entry.getKey(), entry.getValue())) {
                    entry.getValue().completeExceptionally(cause);
                }
            }
        }
    }

    /** 是否已经没有在途请求。 */
    public boolean isIdle() {
        return rpcFutureMap.isEmpty();
    }

    /** 移除目标时，快速失败本 peer 上所有在途调用。 */
    public void failAll(Throwable cause) {
        for (Map.Entry<Long, RpcFuture> entry : rpcFutureMap.entrySet()) {
            if (removePending(entry.getKey(), entry.getValue())) {
                entry.getValue().completeExceptionally(cause);
            }
        }
    }

    private RpcFuture removePending(long requestId) {
        return rpcFutureMap.remove(requestId);
    }

    private boolean removePending(long requestId, RpcFuture expected) {
        return rpcFutureMap.remove(requestId, expected);
    }
}
