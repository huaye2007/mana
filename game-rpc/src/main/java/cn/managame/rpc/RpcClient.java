package cn.managame.rpc;

import cn.managame.common.context.MetadataKeys;
import cn.managame.rpc.connection.ClientRpcConnection;
import cn.managame.rpc.connection.RpcConnection;
import cn.managame.rpc.protocol.Metadata;
import cn.managame.rpc.protocol.RpcCodec;
import cn.managame.serialization.SerializerManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.HashedWheelTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 对内 RPC 客户端：向若干目标实例建立连接组（每目标一个 {@link RpcPeer}），建连后协商握手、
 * 写空闲发心跳保活，按 routeKey 选连接发起 oneway/invoke。
 * <p>每个目标维护固定的 connectionSize 个<b>连接槽位</b>（slot，下标 [0, size)），每个槽位独立自愈：
 * 该槽位的连接失败或断开后，只重连该槽位（按退避+抖动），重连出的新连接沿用原槽位下标。
 * 通过 {@link #connect}/{@link #disconnect} 管理目标成员（宿主可桥接注册中心驱动）。
 * workerGroup / timer 由构造方法内部创建，并在 {@link #close()} 释放。
 */
public class RpcClient extends RpcContainer {

    private static final Logger log = LoggerFactory.getLogger(RpcClient.class);

    static final AttributeKey<Slot> SLOT = AttributeKey.valueOf("game.rpc.slot");

    private final RpcClientConfig config;
    private final RpcClientInternalHandler internalHandler = new RpcClientInternalHandler(this);
    private final Map<String, RpcTarget> targets = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public RpcClient(RpcClientConfig config, RpcMessageHandler handler) {
        config.validate();
        if (handler == null) {
            throw new GameRpcException("handler is required");
        }
        this.config = config;
        this.serializerManager = SerializerManager.getInstance();
        this.handler = handler;
        this.defaultTimeoutMillis = config.getDefaultTimeoutMillis();
        this.maxPendingPerPeer = config.getMaxPendingPerPeer();
        this.workerGroup = new MultiThreadIoEventLoopGroup(0, NioIoHandler.newFactory());
        this.sharedTimer = new HashedWheelTimer();
        this.codec = new RpcCodec(serializerManager, config.getMaxFrameLength());
        handler.bind(this);
    }

    /** 向目标实例建立 connectionSize 个槽位的连接；对同一目标重复调用幂等（不重复建连）。 */
    public synchronized void connect(ConnectionTargetConfig connectionTargetConfig) {
        if (closed) {
            return;
        }
        String key = targetKey(connectionTargetConfig.getServiceName(), connectionTargetConfig.getServiceId());
        RpcTarget existing = targets.get(key);
        if (existing != null && !existing.removed) {
            return; // 已在连接/已连接，幂等
        }
        RpcTarget target = new RpcTarget(connectionTargetConfig);
        targets.put(key, target);
        getOrCreateRpcPeer(connectionTargetConfig.getServiceName(), connectionTargetConfig.getServiceId());
        int size = connectionTargetConfig.getConnectionSize() > 0
                ? connectionTargetConfig.getConnectionSize() : config.getConnectionSize();
        for (int i = 0; i < size; i++) {
            doConnect(new Slot(target, i), config.getReconnectInitialBackoffMillis());
        }
    }

    /** 主动移除目标：停止其所有槽位重连、失败其在途调用、关闭其连接并回收 peer。 */
    public synchronized void disconnect(String serviceName, String serviceId) {
        RpcTarget target = targets.remove(targetKey(serviceName, serviceId));
        if (target != null) {
            target.removed = true; // 阻止后续重连（所有槽位共享此标志）
        }
        RpcPeer peer = removeRpcPeer(serviceName, serviceId);
        if (peer != null) {
            peer.getRpcInvokeManager().failAll(new GameRpcException(
                    "target removed: " + serviceName + "/" + serviceId));
            for (RpcConnection connection : peer.snapshot()) {
                connection.close();
            }
        }
    }

    private void doConnect(Slot slot, long retryBackoffMillis) {
        if (closed || slot.target.removed) {
            return;
        }
        ConnectionTargetConfig cfg = slot.target.config;
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMillis())
                .option(ChannelOption.TCP_NODELAY, config.isTcpNoDelay())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.attr(SLOT).set(slot);
                        codec.configure(channel.pipeline());
                        int heartbeat = config.getHeartbeatIntervalSeconds();
                        int idle = config.getIdleTimeoutSeconds();
                        if (heartbeat > 0 || idle > 0) {
                            // 写空闲发心跳 ping；读空闲（含 pong 都没收到）判死并关闭以触发重连
                            channel.pipeline().addLast("rpcIdle",
                                    new IdleStateHandler(idle, heartbeat, 0, TimeUnit.SECONDS));
                        }
                        channel.pipeline().addLast("rpcInternal", internalHandler);
                        channel.pipeline().addLast("rpcHandler", handler);
                    }
                });
        bootstrap.connect(cfg.getIp(), cfg.getPort()).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                log.warn("rpc connect failed to {}:{} slot#{}, retry base {}ms",
                        cfg.getIp(), cfg.getPort(), slot.index, retryBackoffMillis, future.cause());
                scheduleReconnect(slot, retryBackoffMillis);
            }
        });
    }

    private void scheduleReconnect(Slot slot, long backoffMillis) {
        if (!config.isReconnectEnabled() || closed || slot.target.removed) {
            return;
        }
        long base = Math.min(backoffMillis, config.getReconnectMaxBackoffMillis());
        long next = Math.min(base * 2, config.getReconnectMaxBackoffMillis());
        // 抖动到 [base/2, base]，避免一批槽位对同一恢复中的服务端惊群
        long delay = base / 2 + ThreadLocalRandom.current().nextLong(base / 2 + 1);
        try {
            sharedTimer.newTimeout(timeout -> {
                if (closed || slot.target.removed) {
                    return;
                }
                getMetrics().onReconnectAttempt();
                doConnect(slot, next);
            }, delay, TimeUnit.MILLISECONDS);
        } catch (IllegalStateException stopped) {
            // timer 已在 close() 中停止，忽略
        }
    }

    @Override
    public void removeChannel(Channel channel) {
        super.removeChannel(channel);
        if (closed) {
            return;
        }
        Slot slot = channel.attr(SLOT).get();
        if (slot != null && !slot.target.removed) {
            // 该槽位的连接断开：只重连这个槽位，从初始退避开始
            scheduleReconnect(slot, config.getReconnectInitialBackoffMillis());
        }
    }

    @Override
    protected void onChannelActive(Channel channel) {
        if (closed) {
            return;
        }
        Slot slot = channel.attr(SLOT).get();
        if (slot == null || slot.target.removed) {
            return;
        }
        ConnectionTargetConfig cfg = slot.target.config;
        ClientRpcConnection connection = new ClientRpcConnection(channel);
        connection.setServiceName(cfg.getServiceName());
        connection.setServiceId(cfg.getServiceId());
        connection.setIp(cfg.getIp());
        connection.setPort(cfg.getPort());
        connection.setIndex(slot.index); // 槽位下标随连接走，重连后保持稳定
        // 先只登记到 channel→连接 索引；连接此时还不参与路由，等服务端握手确认后才挂入 peer
        registerConnection(connection);
        connection.writeMsg(buildHandshake());
    }

    /** 收到服务端握手确认（IO 线程）：协商完成，连接才挂入 peer 开始参与路由。 */
    void onHandshakeAck(Channel channel) {
        RpcConnection connection = getRpcConnection(channel);
        if (connection == null) {
            return; // 确认到达前连接已断
        }
        Slot slot = channel.attr(SLOT).get();
        if (slot != null && slot.target.removed) {
            connection.close(); // 协商期间目标已被移除，丢弃
            return;
        }
        getOrCreateRpcPeer(connection.getServiceName(), connection.getServiceId()).add(connection);
        log.info("rpc handshake ack, connection ready: {}/{} slot#{}",
                connection.getServiceName(), connection.getServiceId(), connection.getIndex());
    }

    private RpcRequest buildHandshake() {
        List<Metadata> metadata = new ArrayList<>(3);
        if (config.getServiceName() != null) {
            metadata.add(Metadata.ofString(MetadataKeys.RPC_SERVICE_NAME, config.getServiceName()));
            metadata.add(Metadata.ofString(MetadataKeys.RPC_SERVICE_ID, config.getServiceId()));
        }
        if (config.getAuthToken() != null) {
            metadata.add(Metadata.ofString(MetadataKeys.RPC_AUTH_TOKEN, config.getAuthToken()));
        }
        RpcRequest request = RpcRequest.oneway(RpcInternal.CMD_HANDSHAKE);
        if (!metadata.isEmpty()) {
            request.metadata(metadata.toArray(new Metadata[0]));
        }
        return request;
    }

    public void close() {
        close(config.getShutdownGraceMillis());
    }

    /**
     * 优雅停机：停止重连、拒绝新 invoke，等已在途的请求收到响应（最多 graceMillis），再释放资源。
     * 超过窗口仍未回的在途请求，会在连接关闭时以异常失败。
     */
    public void close(long graceMillis) {
        closed = true;     // 阻止后续重连
        beginShutdown();   // 拒绝新 invoke，已在途的继续等响应
        int remaining = awaitDrain(graceMillis);
        if (remaining > 0) {
            log.warn("rpc client closing with {} in-flight requests after {}ms grace", remaining, graceMillis);
        }
        workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        sharedTimer.stop();
    }

    private static String targetKey(String serviceName, String serviceId) {
        return (serviceName == null ? "" : serviceName) + "/" + (serviceId == null ? "" : serviceId);
    }

    /** 一个连接目标的状态：配置 + 是否已被主动移除（移除后停止重连）。 */
    private static final class RpcTarget {
        final ConnectionTargetConfig config;
        volatile boolean removed;

        RpcTarget(ConnectionTargetConfig config) {
            this.config = config;
        }
    }

    /** 目标下的一个连接槽位：固定下标，独立自愈。重连永远是"哪个槽位断了重连哪个槽位"。 */
    private static final class Slot {
        final RpcTarget target;
        final int index;

        Slot(RpcTarget target, int index) {
            this.target = target;
            this.index = index;
        }
    }
}
