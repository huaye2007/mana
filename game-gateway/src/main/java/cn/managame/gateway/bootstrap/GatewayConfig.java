package cn.managame.gateway.bootstrap;

import cn.managame.config.manager.GameConfigManager;

/**
 * 网关运行配置，从分层配置管理器（命令行 / -D / 环境变量 / 本地文件）读取。
 * 键统一 {@code game.gateway.*} 前缀；注册中心复用 game-dev 的 {@code game.registry.*} 键。
 */
public final class GatewayConfig {

    // 外网接入
    private int tcpPort = 9000;
    private int wsPort = 9001;            // 0 = 关闭 WebSocket 接入
    private String wsPath = "/ws";
    private int readerIdleSeconds = 180;
    private int serverId = 1;             // 连接 id 生成器 + 自注册实例 id 后缀

    // 自身注册（供上层 LB/监控发现网关）
    private String serviceName = "game-gateway";
    private String advertiseAddress = "127.0.0.1";

    // 后端转发
    private String backendService = "game-dev"; // watch 的后端服务名
    private int backendConnections = 4;          // 每后端实例的连接池大小
    private int loginCommand = 1000;             // 登录命令（对齐 game-dev LoginController @GameMethod(1000)）

    // 会话级限流
    private double ratePps = 50;
    private double rateBurst = 100;

    // IP 级防护
    private int ddosMaxConnPerIp = 100;
    private double ddosPpsPerIp = 500;
    private double ddosBurstPerIp = 1000;

    // 注册中心
    private String registryType = "memory";
    private String registryEndpoints = "local";

    public static GatewayConfig load(GameConfigManager config) {
        GatewayConfig c = new GatewayConfig();
        c.tcpPort = intCfg(config, "game.gateway.tcp.port", "GAME_GATEWAY_TCP_PORT", c.tcpPort);
        c.wsPort = intCfg(config, "game.gateway.ws.port", "GAME_GATEWAY_WS_PORT", c.wsPort);
        c.wsPath = cfg(config, "game.gateway.ws.path", "GAME_GATEWAY_WS_PATH", c.wsPath);
        c.readerIdleSeconds = intCfg(config, "game.gateway.reader.idle.seconds",
                "GAME_GATEWAY_READER_IDLE_SECONDS", c.readerIdleSeconds);
        c.serverId = intCfg(config, "game.gateway.server.id", "GAME_GATEWAY_SERVER_ID", c.serverId);
        c.serviceName = cfg(config, "game.gateway.service.name", "GAME_GATEWAY_SERVICE_NAME", c.serviceName);
        c.advertiseAddress = cfg(config, "game.gateway.address", "GAME_GATEWAY_ADDRESS", c.advertiseAddress);
        c.backendService = cfg(config, "game.gateway.backend.service",
                "GAME_GATEWAY_BACKEND_SERVICE", c.backendService);
        c.backendConnections = intCfg(config, "game.gateway.backend.connections",
                "GAME_GATEWAY_BACKEND_CONNECTIONS", c.backendConnections);
        c.loginCommand = intCfg(config, "game.gateway.login.command", "GAME_GATEWAY_LOGIN_COMMAND", c.loginCommand);
        c.ratePps = doubleCfg(config, "game.gateway.rate.pps", "GAME_GATEWAY_RATE_PPS", c.ratePps);
        c.rateBurst = doubleCfg(config, "game.gateway.rate.burst", "GAME_GATEWAY_RATE_BURST", c.rateBurst);
        c.ddosMaxConnPerIp = intCfg(config, "game.gateway.ddos.max-conn-per-ip",
                "GAME_GATEWAY_DDOS_MAX_CONN_PER_IP", c.ddosMaxConnPerIp);
        c.ddosPpsPerIp = doubleCfg(config, "game.gateway.ddos.pps-per-ip",
                "GAME_GATEWAY_DDOS_PPS_PER_IP", c.ddosPpsPerIp);
        c.ddosBurstPerIp = doubleCfg(config, "game.gateway.ddos.burst-per-ip",
                "GAME_GATEWAY_DDOS_BURST_PER_IP", c.ddosBurstPerIp);
        c.registryType = cfg(config, "game.registry.type", "GAME_REGISTRY_TYPE", c.registryType);
        c.registryEndpoints = cfg(config, "game.registry.endpoints", "GAME_REGISTRY_ENDPOINTS", c.registryEndpoints);
        return c;
    }

    /** 读取配置：先按标准键查（命令行/-D/本地文件已分层合并），再兜底旧式大写环境变量键。 */
    private static String cfg(GameConfigManager config, String key, String envKey, String defaultValue) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            value = config.get(envKey);
        }
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    private static int intCfg(GameConfigManager config, String key, String envKey, int defaultValue) {
        return Integer.parseInt(cfg(config, key, envKey, Integer.toString(defaultValue)));
    }

    private static double doubleCfg(GameConfigManager config, String key, String envKey, double defaultValue) {
        return Double.parseDouble(cfg(config, key, envKey, Double.toString(defaultValue)));
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public int getWsPort() {
        return wsPort;
    }

    public boolean isWebSocketEnabled() {
        return wsPort > 0;
    }

    public String getWsPath() {
        return wsPath;
    }

    public int getReaderIdleSeconds() {
        return readerIdleSeconds;
    }

    public int getServerId() {
        return serverId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getAdvertiseAddress() {
        return advertiseAddress;
    }

    public String getBackendService() {
        return backendService;
    }

    public int getBackendConnections() {
        return backendConnections;
    }

    public int getLoginCommand() {
        return loginCommand;
    }

    public double getRatePps() {
        return ratePps;
    }

    public double getRateBurst() {
        return rateBurst;
    }

    public int getDdosMaxConnPerIp() {
        return ddosMaxConnPerIp;
    }

    public double getDdosPpsPerIp() {
        return ddosPpsPerIp;
    }

    public double getDdosBurstPerIp() {
        return ddosBurstPerIp;
    }

    public String getRegistryType() {
        return registryType;
    }

    public String getRegistryEndpoints() {
        return registryEndpoints;
    }
}
