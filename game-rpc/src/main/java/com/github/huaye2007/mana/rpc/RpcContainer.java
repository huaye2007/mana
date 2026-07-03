package com.github.huaye2007.mana.rpc;

import com.github.huaye2007.mana.rpc.connection.RpcConnection;
import com.github.huaye2007.mana.rpc.protocol.RpcCodec;
import com.github.huaye2007.mana.serialization.SerializationType;
import com.github.huaye2007.mana.serialization.SerializerManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.util.HashedWheelTimer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RPC 端点基类（客户端 / 服务端共用）：维护 channel→连接、(serviceName,serviceId)→peer 两级索引，
 * 提供按服务或按连接的 oneway/invoke。子类负责建连（{@link RpcClient}）或监听（{@link RpcServer}）。
 */
public class RpcContainer {

    private final Map<Channel, RpcConnection> rpcConnectionMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, RpcPeer>> service2RpcPeerMap = new ConcurrentHashMap<>();
    private final AtomicLong connectionIdGen = new AtomicLong();
    private final RpcMetrics metrics = new RpcMetrics();

    protected SerializerManager serializerManager;       // 共享
    protected HashedWheelTimer sharedTimer;              // 共享
    protected MultiThreadIoEventLoopGroup workerGroup;   // 共享或自持
    protected RpcMessageHandler handler;
    protected RpcCodec codec;                            // 广播时单次编码复用
    protected long defaultTimeoutMillis = 5000;
    protected byte defaultSerialType = SerializationType.JSON.typeId();
    protected int maxPendingPerPeer;                    // 单 peer 在途上限，0 = 不限
    protected boolean reclaimEmptyPeers;               // 连接清空后是否回收 peer（服务端开，客户端靠重连保留）
    private volatile boolean shuttingDown;             // 优雅停机中：拒绝新 invoke，等在途排空

    // ---- 索引维护 ----

    public RpcPeer getOrCreateRpcPeer(String serviceName, String serviceId) {
        String name = normalize(serviceName);
        Map<String, RpcPeer> peerMap = service2RpcPeerMap.computeIfAbsent(name, k -> new ConcurrentHashMap<>());
        return peerMap.computeIfAbsent(normalize(serviceId), id -> new RpcPeer(this, name, id));
    }

    public RpcPeer getRpcPeer(String serviceName, String serviceId) {
        // serviceName 可能为 null（握手前的服务端连接断开），normalize 后查，避免 ConcurrentHashMap.get(null) NPE
        Map<String, RpcPeer> peerMap = service2RpcPeerMap.get(normalize(serviceName));
        if (peerMap == null) {
            return null;
        }
        return peerMap.get(normalize(serviceId));
    }

    public void registerConnection(RpcConnection connection) {
        rpcConnectionMap.put(connection.getChannel(), connection);
    }

    public RpcConnection getRpcConnection(Channel channel) {
        return rpcConnectionMap.get(channel);
    }

    public void removeChannel(Channel channel) {
        RpcConnection connection = rpcConnectionMap.remove(channel);
        if (connection == null) {
            return;
        }
        metrics.onConnectionClosed();
        RpcPeer peer = getRpcPeer(connection.getServiceName(), connection.getServiceId());
        if (peer != null) {
            peer.remove(connection);
            peer.getRpcInvokeManager().failConnection(connection.getConnectionId(),
                    new GameRpcException("connection closed: " + connection.getRemoteAddress()));
            if (reclaimEmptyPeers && peer.isEmpty()) {
                // 服务端：对端连接清空（如客户端永久下线）后回收空 peer，避免 map 随身份只增不减
                reclaimIfEmpty(connection.getServiceName(), connection.getServiceId());
            }
        }
    }

    /** 原子地移除该身份下已清空的 peer（仍有连接则保留）。 */
    private void reclaimIfEmpty(String serviceName, String serviceId) {
        Map<String, RpcPeer> peerMap = service2RpcPeerMap.get(normalize(serviceName));
        if (peerMap != null) {
            peerMap.computeIfPresent(normalize(serviceId), (k, p) -> p.isEmpty() ? null : p);
        }
    }

    /** 移除并返回某身份的 peer（用于主动移除目标）。 */
    protected RpcPeer removeRpcPeer(String serviceName, String serviceId) {
        Map<String, RpcPeer> peerMap = service2RpcPeerMap.get(normalize(serviceName));
        if (peerMap == null) {
            return null;
        }
        return peerMap.remove(normalize(serviceId));
    }

    long nextConnectionId() {
        return connectionIdGen.incrementAndGet();
    }

    /** 连接激活回调（IO 线程），子类登记连接。 */
    protected void onChannelActive(Channel channel) {
    }

    // ---- 发送：按服务 ----

    public void oneway(String serviceName, String serviceId, RpcRequest rpcRequest) {
        RpcPeer peer = getRpcPeer(serviceName, serviceId);
        if (peer == null) {
            throw new GameRpcException("no peer for service " + serviceName + "/" + serviceId);
        }
        ensureSerialType(rpcRequest);
        peer.oneway(rpcRequest);
    }

    public RpcFuture invoke(String serviceName, String serviceId, RpcRequest rpcRequest) {
        RpcPeer peer = getRpcPeer(serviceName, serviceId);
        if (peer == null) {
            return RpcFuture.failed(new GameRpcException("no peer for service " + serviceName + "/" + serviceId));
        }
        ensureSerialType(rpcRequest);
        return peer.invoke(rpcRequest);
    }

    public <V> void invoke(String serviceName, String serviceId, RpcRequest rpcRequest, RpcCallback<V> rpcCallback) {
        RpcPeer peer = getRpcPeer(serviceName, serviceId);
        if (peer == null) {
            rpcCallback.onException(new GameRpcException("no peer for service " + serviceName + "/" + serviceId));
            return;
        }
        ensureSerialType(rpcRequest);
        peer.invoke(rpcRequest, rpcCallback);
    }

    // ---- 发送：按连接 ----

    public void oneway(RpcConnection rpcConnection, RpcRequest rpcRequest) {
        if (!rpcConnection.isWritable()) {
            // 出站缓冲已堆到高水位，oneway 直接丢弃（fire-and-forget），否则洪峰会撑爆堆外内存
            metrics.onRejectedNotWritable();
            return;
        }
        ensureSerialType(rpcRequest);
        // fire-and-forget：不挂写回调，避免热路径每次分配 IWriteCallback + listener
        rpcConnection.writeMsg(rpcRequest);
        metrics.onOnewaySent();
    }

    public RpcFuture invoke(RpcConnection rpcConnection, RpcRequest rpcRequest) {
        RpcPeer peer = peerOf(rpcConnection);
        if (peer == null) {
            return RpcFuture.failed(noPeer(rpcConnection));
        }
        ensureSerialType(rpcRequest);
        return peer.getRpcInvokeManager().invoke(rpcConnection, rpcRequest);
    }

    public <V> void invoke(RpcConnection rpcConnection, RpcRequest rpcRequest, RpcCallback<V> rpcCallback) {
        RpcPeer peer = peerOf(rpcConnection);
        if (peer == null) {
            rpcCallback.onException(noPeer(rpcConnection));
            return;
        }
        ensureSerialType(rpcRequest);
        peer.getRpcInvokeManager().invoke(rpcConnection, rpcRequest, rpcCallback);
    }

    /**
     * 向某服务的每个实例各发一条 oneway（每实例按 routeKey 选一条连接）。
     * body 只序列化一次，再对各连接写同一帧的 retainedDuplicate，避免逐个目标重复序列化 + 重复分配。
     */
    public void broadcast(String serviceName, RpcRequest rpcRequest) {
        Map<String, RpcPeer> peerMap = service2RpcPeerMap.get(normalize(serviceName));
        if (peerMap == null || peerMap.isEmpty()) {
            return;
        }
        ensureSerialType(rpcRequest);
        ByteBuf frame = codec.encode(rpcRequest, ByteBufAllocator.DEFAULT); // 单次编码
        try {
            for (RpcPeer peer : peerMap.values()) {
                RpcConnection connection = peer.select(rpcRequest.getRouteKey());
                if (connection != null && connection.isWritable()) {
                    connection.writeMsg(frame.retainedDuplicate());
                    metrics.onOnewaySent();
                } else if (connection == null) {
                    metrics.onRejectedNoConnection();
                } else {
                    metrics.onRejectedNotWritable();
                }
            }
        } finally {
            frame.release();
        }
    }

    /** 响应到达（IO 线程），路由到对应 peer 的在途调用。 */
    public void onResponse(RpcConnection connection, RpcResponse response) {
        RpcPeer peer = peerOf(connection);
        if (peer == null) {
            metrics.onLateResponse();
            return;
        }
        peer.getRpcInvokeManager().complete(response);
    }

    protected void ensureSerialType(RpcRequest request) {
        if (request.getSerialType() == 0) {
            request.serialType(defaultSerialType);
        }
    }

    private RpcPeer peerOf(RpcConnection connection) {
        return getRpcPeer(connection.getServiceName(), connection.getServiceId());
    }

    private static GameRpcException noPeer(RpcConnection connection) {
        return new GameRpcException("no peer for connection "
                + connection.getServiceName() + "/" + connection.getServiceId());
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    // ---- 优雅停机 ----

    /** 进入停机态：新的 invoke 一律快速失败，已在途的请求不受影响、继续等响应。 */
    protected void beginShutdown() {
        shuttingDown = true;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /** 所有 peer 当前在途请求总数。 */
    public int pendingRequestCount() {
        int sum = 0;
        for (Map<String, RpcPeer> peerMap : service2RpcPeerMap.values()) {
            for (RpcPeer peer : peerMap.values()) {
                sum += peer.getRpcInvokeManager().pendingCount();
            }
        }
        return sum;
    }

    /** 阻塞等待在途请求排空，最多 graceMillis；返回剩余在途数（0 表示已排空）。 */
    protected int awaitDrain(long graceMillis) {
        long deadline = System.nanoTime() + graceMillis * 1_000_000L;
        int pending;
        while ((pending = pendingRequestCount()) > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return pending;
    }

    // ---- 访问器 ----

    public RpcMetrics getMetrics() {
        return metrics;
    }

    public SerializerManager getSerializerManager() {
        return serializerManager;
    }

    public HashedWheelTimer getTimer() {
        return sharedTimer;
    }

    public long getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }

    public byte getDefaultSerialType() {
        return defaultSerialType;
    }

    public int getMaxPendingPerPeer() {
        return maxPendingPerPeer;
    }
}
