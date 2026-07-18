package cn.managame.rpc;

import cn.managame.rpc.protocol.RpcCodec;

/**
 * 对内 RPC 服务端配置，流式 setter。
 */
public final class RpcServerConfig {

    private int port;
    private int bossThreads = 1;
    private int workerThreads; // 0 = Netty 默认（CPU*2）
    private boolean tcpNoDelay = true;
    private int backlog = 1024;
    private long handshakeTimeoutMillis = 5000;
    private int idleTimeoutSeconds = 30; // 读空闲多久判定连接已死并关闭，0 关闭检测
    private String authToken; // 非 null 时校验客户端握手 token，不一致断连
    private int maxFrameLength = RpcCodec.DEFAULT_MAX_FRAME_LENGTH;
    private long defaultTimeoutMillis = 5000; // 服务端发起 invoke 的默认超时，可被 RpcRequest.timeoutMillis 覆盖
    private int maxPendingPerPeer = 100_000;  // 单个 peer 在途请求上限，超过拒绝，防对端假死时 OOM；0 = 不限
    private long shutdownGraceMillis = 5000;  // close() 停止收新连接后，给在途请求/响应刷写的最长窗口，0 = 不等

    /**
     * 校验配置合法性，{@link RpcServer#start()} 时调用，把配置错误提前到启动期暴露。
     */
    public void validate() {
        require(port > 0 && port <= 65535, "port must be in (0, 65535], got " + port);
        require(bossThreads >= 1, "bossThreads must be >= 1, got " + bossThreads);
        require(workerThreads >= 0, "workerThreads must be >= 0, got " + workerThreads);
        require(backlog > 0, "backlog must be > 0, got " + backlog);
        require(handshakeTimeoutMillis > 0, "handshakeTimeoutMillis must be > 0, got " + handshakeTimeoutMillis);
        require(idleTimeoutSeconds >= 0, "idleTimeoutSeconds must be >= 0, got " + idleTimeoutSeconds);
        require(maxFrameLength > 0, "maxFrameLength must be > 0, got " + maxFrameLength);
        require(defaultTimeoutMillis > 0, "defaultTimeoutMillis must be > 0, got " + defaultTimeoutMillis);
        require(maxPendingPerPeer >= 0, "maxPendingPerPeer must be >= 0, got " + maxPendingPerPeer);
        require(shutdownGraceMillis >= 0, "shutdownGraceMillis must be >= 0, got " + shutdownGraceMillis);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException("invalid RpcServerConfig: " + message);
        }
    }

    public RpcServerConfig port(int port) {
        this.port = port;
        return this;
    }

    public RpcServerConfig bossThreads(int bossThreads) {
        this.bossThreads = bossThreads;
        return this;
    }

    public RpcServerConfig workerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
        return this;
    }

    public RpcServerConfig tcpNoDelay(boolean tcpNoDelay) {
        this.tcpNoDelay = tcpNoDelay;
        return this;
    }

    public RpcServerConfig backlog(int backlog) {
        this.backlog = backlog;
        return this;
    }

    public RpcServerConfig idleTimeoutSeconds(int idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        return this;
    }

    public RpcServerConfig authToken(String authToken) {
        this.authToken = authToken;
        return this;
    }

    public RpcServerConfig handshakeTimeoutMillis(long handshakeTimeoutMillis) {
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
        return this;
    }

    public RpcServerConfig maxFrameLength(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
        return this;
    }

    public RpcServerConfig defaultTimeoutMillis(long defaultTimeoutMillis) {
        this.defaultTimeoutMillis = defaultTimeoutMillis;
        return this;
    }

    public RpcServerConfig maxPendingPerPeer(int maxPendingPerPeer) {
        this.maxPendingPerPeer = maxPendingPerPeer;
        return this;
    }

    public RpcServerConfig shutdownGraceMillis(long shutdownGraceMillis) {
        this.shutdownGraceMillis = shutdownGraceMillis;
        return this;
    }

    public long getShutdownGraceMillis() {
        return shutdownGraceMillis;
    }

    public int getPort() {
        return port;
    }

    public int getBossThreads() {
        return bossThreads;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }

    public int getBacklog() {
        return backlog;
    }

    public int getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public String getAuthToken() {
        return authToken;
    }

    public long getHandshakeTimeoutMillis() {
        return handshakeTimeoutMillis;
    }

    public int getMaxFrameLength() {
        return maxFrameLength;
    }

    public long getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }

    public int getMaxPendingPerPeer() {
        return maxPendingPerPeer;
    }
}
