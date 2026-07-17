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
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
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

    static final AttributeKey<Attempt> ATTEMPT = AttributeKey.valueOf("game.rpc.attempt");

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
        int size = connectionTargetConfig.getConnectionSize() > 0
                ? connectionTargetConfig.getConnectionSize() : config.getConnectionSize();
        RpcTarget target = new RpcTarget(connectionTargetConfig, size,
                config.getReconnectInitialBackoffMillis());
        targets.put(key, target);
        RpcPeer peer = getOrCreateRpcPeer(connectionTargetConfig.getServiceName(), connectionTargetConfig.getServiceId());
        peer.configureFixedConnectionSlots(size);
        for (Slot slot : target.slots) {
            startConnect(slot, false);
        }
    }

    /** 主动移除目标：停止其所有槽位重连、失败其在途调用、关闭其连接并回收 peer。 */
    public synchronized void disconnect(String serviceName, String serviceId) {
        RpcTarget target = targets.remove(targetKey(serviceName, serviceId));
        List<Channel> slotChannels = new ArrayList<>();
        if (target != null) {
            target.removed = true; // 阻止后续重连（所有槽位共享此标志）
            for (Slot slot : target.slots) {
                synchronized (slot) {
                    slot.state = SlotState.REMOVED;
                    slot.generation++;
                    if (slot.reconnectTask != null) {
                        slot.reconnectTask.cancel();
                        slot.reconnectTask = null;
                    }
                    if (slot.channel != null) {
                        slotChannels.add(slot.channel);
                        slot.channel = null;
                    }
                }
            }
        }
        RpcPeer peer = removeRpcPeer(serviceName, serviceId);
        if (peer != null) {
            peer.getRpcInvokeManager().failAll(new GameRpcException(
                    "target removed: " + serviceName + "/" + serviceId));
            for (RpcConnection connection : peer.snapshot()) {
                connection.close();
            }
        }
        // CONNECTING/HANDSHAKING 阶段的 channel 尚未进入 peer，也必须由 disconnect 主动关闭。
        for (Channel channel : slotChannels) {
            channel.close();
        }
    }

    private void startConnect(Slot slot, boolean reconnectAttempt) {
        Attempt attempt;
        synchronized (slot) {
            if (closed || slot.target.removed || slot.state != SlotState.IDLE) {
                return;
            }
            slot.state = SlotState.CONNECTING;
            attempt = new Attempt(slot, ++slot.generation, reconnectAttempt);
        }
        ConnectionTargetConfig cfg = slot.target.config;
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMillis())
                .option(ChannelOption.TCP_NODELAY, config.isTcpNoDelay());
        if (config.getWriteBufferLowWaterMark() > 0) {
            bootstrap.option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                    new WriteBufferWaterMark(config.getWriteBufferLowWaterMark(),
                            config.getWriteBufferHighWaterMark()));
        }
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.attr(ATTEMPT).set(attempt);
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
        try {
            var connectFuture = bootstrap.connect(cfg.getIp(), cfg.getPort());
            synchronized (slot) {
                if (slot.generation == attempt.generation && slot.state == SlotState.CONNECTING) {
                    slot.channel = connectFuture.channel();
                } else {
                    connectFuture.channel().close();
                }
            }
            connectFuture.addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    log.warn("rpc connect failed to {}:{} slot#{}, retry base {}ms",
                            cfg.getIp(), cfg.getPort(), slot.index, currentBackoff(slot), future.cause());
                    onAttemptTerminated(attempt);
                }
            });
        } catch (Throwable connectFailure) {
            log.warn("rpc connect setup failed to {}:{} slot#{}", cfg.getIp(), cfg.getPort(), slot.index,
                    connectFailure);
            onAttemptTerminated(attempt);
        }
    }

    /** 连接失败和 channelInactive 都会到这里；generation + reconnectTask 保证只安排一次重连。 */
    private void onAttemptTerminated(Attempt attempt) {
        Slot slot = attempt.slot;
        synchronized (slot) {
            if (attempt.generation != slot.generation || slot.state == SlotState.REMOVED) {
                return;
            }
            slot.channel = null;
            slot.state = SlotState.IDLE;
            scheduleReconnectLocked(slot);
        }
    }

    private void scheduleReconnectLocked(Slot slot) {
        if (!config.isReconnectEnabled() || closed || slot.target.removed || slot.reconnectTask != null) {
            return;
        }
        long base = Math.min(slot.nextBackoffMillis, config.getReconnectMaxBackoffMillis());
        slot.nextBackoffMillis = Math.min(base * 2, config.getReconnectMaxBackoffMillis());
        // 抖动到 [base/2, base]，避免一批槽位对同一恢复中的服务端惊群
        long delay = base / 2 + ThreadLocalRandom.current().nextLong(base / 2 + 1);
        try {
            slot.reconnectTask = sharedTimer.newTimeout(timeout -> {
                boolean launch;
                synchronized (slot) {
                    if (slot.reconnectTask != timeout) {
                        return;
                    }
                    slot.reconnectTask = null;
                    launch = !closed && !slot.target.removed && slot.state == SlotState.IDLE;
                }
                if (launch) {
                    getMetrics().onReconnectAttempt();
                    startConnect(slot, true);
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (IllegalStateException stopped) {
            // timer 已在 close() 中停止，忽略
            slot.reconnectTask = null;
        }
    }

    private static long currentBackoff(Slot slot) {
        synchronized (slot) {
            return slot.nextBackoffMillis;
        }
    }

    @Override
    public void removeChannel(Channel channel) {
        super.removeChannel(channel);
        Attempt attempt = channel.attr(ATTEMPT).get();
        if (attempt != null) {
            onAttemptTerminated(attempt);
        }
    }

    @Override
    protected void onChannelActive(Channel channel) {
        Attempt attempt = channel.attr(ATTEMPT).get();
        if (attempt == null) {
            channel.close();
            return;
        }
        Slot slot = attempt.slot;
        ConnectionTargetConfig cfg = slot.target.config;
        synchronized (slot) {
            if (closed || slot.target.removed || attempt.generation != slot.generation
                    || slot.state != SlotState.CONNECTING) {
                channel.close();
                return;
            }
            slot.state = SlotState.HANDSHAKING;
            slot.channel = channel;
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
    }

    /** 收到服务端握手确认（IO 线程）：协商完成，连接才挂入 peer 开始参与路由。 */
    void onHandshakeAck(Channel channel) {
        RpcConnection connection = getRpcConnection(channel);
        if (connection == null) {
            return; // 确认到达前连接已断
        }
        Attempt attempt = channel.attr(ATTEMPT).get();
        if (attempt == null) {
            connection.close();
            return;
        }
        Slot slot = attempt.slot;
        boolean reconnectSuccess;
        synchronized (slot) {
            if (closed || slot.target.removed || attempt.generation != slot.generation
                    || slot.state != SlotState.HANDSHAKING || slot.channel != channel) {
                connection.close();
                return;
            }
            getOrCreateRpcPeer(connection.getServiceName(), connection.getServiceId())
                    .setConnectionSlot(slot.index, connection);
            slot.state = SlotState.READY;
            slot.nextBackoffMillis = config.getReconnectInitialBackoffMillis();
            reconnectSuccess = attempt.reconnectAttempt;
        }
        if (reconnectSuccess) {
            getMetrics().onReconnectSuccess();
        }
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
        boolean drained = awaitDrain(graceMillis);
        if (!drained) {
            log.warn("rpc client closing with in-flight requests after {}ms grace", graceMillis);
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
        final Slot[] slots;
        volatile boolean removed;

        RpcTarget(ConnectionTargetConfig config, int size, long initialBackoffMillis) {
            this.config = config;
            this.slots = new Slot[size];
            for (int i = 0; i < size; i++) {
                slots[i] = new Slot(this, i, initialBackoffMillis);
            }
        }
    }

    /** 目标下的一个连接槽位：固定下标，独立自愈。重连永远是"哪个槽位断了重连哪个槽位"。 */
    private static final class Slot {
        final RpcTarget target;
        final int index;
        SlotState state = SlotState.IDLE;
        long generation;
        long nextBackoffMillis;
        Timeout reconnectTask;
        Channel channel;

        Slot(RpcTarget target, int index, long initialBackoffMillis) {
            this.target = target;
            this.index = index;
            this.nextBackoffMillis = initialBackoffMillis;
        }
    }

    private record Attempt(Slot slot, long generation, boolean reconnectAttempt) {
    }

    private enum SlotState {
        IDLE, CONNECTING, HANDSHAKING, READY, REMOVED
    }
}
