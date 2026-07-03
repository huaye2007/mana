package com.github.huaye2007.mana.rpc;

import com.github.huaye2007.mana.rpc.protocol.RpcCodec;

public final class RpcClientConfig {

    private String serviceName; // 客户端自身的服务名，建连后通过握手报给服务端；null 则不发握手
    private String serviceId;   // 客户端自身的实例 ID
    private String authToken;   // 握手鉴权 token，null 不携带；服务端 HandshakeHandler 配置了 token 时必须一致
    private int connectionSize = 1;
    private boolean tcpNoDelay = true;
    private int connectTimeoutMillis = 3000;
    private long defaultTimeoutMillis = 5000; // 客户端发起 invoke 的默认超时，可被 RpcRequest.timeoutMillis 覆盖
    private int maxPendingPerPeer = 100_000;  // 单个 peer 在途请求上限，超过拒绝，防对端假死时 OOM；0 = 不限
    private int maxFrameLength = RpcCodec.DEFAULT_MAX_FRAME_LENGTH;
    private int heartbeatIntervalSeconds = 10; // 写空闲多久发一次心跳 ping，0 关闭心跳
    private int idleTimeoutSeconds = 30; // 读空闲多久判定连接已死并关闭（触发重连）
    private boolean reconnectEnabled = true;
    private long reconnectInitialBackoffMillis = 1000;
    private long reconnectMaxBackoffMillis = 30_000;
    private long shutdownGraceMillis = 5000; // close() 等在途请求排空的最长时间，0 = 不等
    private int writeBufferLowWaterMark;  // 出站缓冲低水位，0 = Netty 默认（32KB）
    private int writeBufferHighWaterMark; // 出站缓冲高水位（isWritable 阈值），0 = Netty 默认（64KB）

    public void validate() {
        require(connectionSize >= 1, "connectionSize must be >= 1, got " + connectionSize);
        require(connectTimeoutMillis > 0, "connectTimeoutMillis must be > 0, got " + connectTimeoutMillis);
        require(defaultTimeoutMillis > 0, "defaultTimeoutMillis must be > 0, got " + defaultTimeoutMillis);
        require(maxPendingPerPeer >= 0, "maxPendingPerPeer must be >= 0, got " + maxPendingPerPeer);
        require(shutdownGraceMillis >= 0, "shutdownGraceMillis must be >= 0, got " + shutdownGraceMillis);
        require(maxFrameLength > 0, "maxFrameLength must be > 0, got " + maxFrameLength);
        if (heartbeatIntervalSeconds > 0) {
            // 读空闲超时小于等于心跳间隔时，连接会在两次心跳之间被误判死亡，反复断连重连
            require(idleTimeoutSeconds > heartbeatIntervalSeconds,
                    "idleTimeoutSeconds(" + idleTimeoutSeconds + ") must be > heartbeatIntervalSeconds("
                            + heartbeatIntervalSeconds + ")");
        }
        if (reconnectEnabled) {
            require(reconnectInitialBackoffMillis > 0,
                    "reconnectInitialBackoffMillis must be > 0, got " + reconnectInitialBackoffMillis);
            require(reconnectMaxBackoffMillis >= reconnectInitialBackoffMillis,
                    "reconnectMaxBackoffMillis(" + reconnectMaxBackoffMillis
                            + ") must be >= reconnectInitialBackoffMillis(" + reconnectInitialBackoffMillis + ")");
        }
        if (writeBufferLowWaterMark != 0 || writeBufferHighWaterMark != 0) {
            require(writeBufferLowWaterMark > 0 && writeBufferHighWaterMark > writeBufferLowWaterMark,
                    "write buffer water marks must satisfy 0 < low(" + writeBufferLowWaterMark
                            + ") < high(" + writeBufferHighWaterMark + "), or both 0 for Netty defaults");
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException("invalid RpcClientConfig: " + message);
        }
    }

    public RpcClientConfig serviceName(String serviceName) {
        this.serviceName = serviceName;
        return this;
    }

    public RpcClientConfig serviceId(String serviceId) {
        this.serviceId = serviceId;
        return this;
    }

    public RpcClientConfig authToken(String authToken) {
        this.authToken = authToken;
        return this;
    }

    public RpcClientConfig connectionSize(int connectionSize) {
        this.connectionSize = connectionSize;
        return this;
    }

    public RpcClientConfig tcpNoDelay(boolean tcpNoDelay) {
        this.tcpNoDelay = tcpNoDelay;
        return this;
    }

    public RpcClientConfig connectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        return this;
    }

    public RpcClientConfig defaultTimeoutMillis(long defaultTimeoutMillis) {
        this.defaultTimeoutMillis = defaultTimeoutMillis;
        return this;
    }

    public RpcClientConfig maxFrameLength(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
        return this;
    }

    public RpcClientConfig heartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        return this;
    }

    public RpcClientConfig idleTimeoutSeconds(int idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        return this;
    }

    public RpcClientConfig reconnectEnabled(boolean reconnectEnabled) {
        this.reconnectEnabled = reconnectEnabled;
        return this;
    }

    public RpcClientConfig reconnectInitialBackoffMillis(long reconnectInitialBackoffMillis) {
        this.reconnectInitialBackoffMillis = reconnectInitialBackoffMillis;
        return this;
    }

    public RpcClientConfig reconnectMaxBackoffMillis(long reconnectMaxBackoffMillis) {
        this.reconnectMaxBackoffMillis = reconnectMaxBackoffMillis;
        return this;
    }

    public RpcClientConfig writeBufferWaterMark(int lowWaterMark, int highWaterMark) {
        this.writeBufferLowWaterMark = lowWaterMark;
        this.writeBufferHighWaterMark = highWaterMark;
        return this;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getServiceId() {
        return serviceId;
    }

    public String getAuthToken() {
        return authToken;
    }

    public int getConnectionSize() {
        return connectionSize;
    }

    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public long getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }

    public RpcClientConfig maxPendingPerPeer(int maxPendingPerPeer) {
        this.maxPendingPerPeer = maxPendingPerPeer;
        return this;
    }

    public int getMaxPendingPerPeer() {
        return maxPendingPerPeer;
    }

    public RpcClientConfig shutdownGraceMillis(long shutdownGraceMillis) {
        this.shutdownGraceMillis = shutdownGraceMillis;
        return this;
    }

    public long getShutdownGraceMillis() {
        return shutdownGraceMillis;
    }

    public int getMaxFrameLength() {
        return maxFrameLength;
    }

    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public int getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public boolean isReconnectEnabled() {
        return reconnectEnabled;
    }

    public long getReconnectInitialBackoffMillis() {
        return reconnectInitialBackoffMillis;
    }

    public long getReconnectMaxBackoffMillis() {
        return reconnectMaxBackoffMillis;
    }

    public int getWriteBufferLowWaterMark() {
        return writeBufferLowWaterMark;
    }

    public int getWriteBufferHighWaterMark() {
        return writeBufferHighWaterMark;
    }
}
