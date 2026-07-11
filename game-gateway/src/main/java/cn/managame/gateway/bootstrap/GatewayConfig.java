package cn.managame.gateway.bootstrap;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Immutable, validated gateway settings with a small dependency-free loader. */
public record GatewayConfig(int tcpPort, int wsPort, String wsPath, int readerIdleSeconds,
                            int serverId, String serviceName, String instanceId, String advertiseAddress,
                            String backendService, int backendConnections, int loginCommand,
                            double ratePps, double rateBurst, int ddosMaxConnectionsPerIp,
                            double ddosPpsPerIp, double ddosBurstPerIp,
                            String registryType, String registryEndpoints, String rpcAuthToken) {
    public GatewayConfig {
        requirePort(tcpPort, false, "tcpPort");
        requirePort(wsPort, true, "wsPort");
        if (wsPath == null || wsPath.isBlank()) wsPath = "/ws";
        if (!wsPath.startsWith("/")) wsPath = '/' + wsPath;
        if (readerIdleSeconds < 0) throw new IllegalArgumentException("readerIdleSeconds must be non-negative");
        if (serverId < 0) throw new IllegalArgumentException("serverId must be non-negative");
        serviceName = requireText(serviceName, "serviceName");
        instanceId = requireText(instanceId, "instanceId");
        advertiseAddress = requireText(advertiseAddress, "advertiseAddress");
        backendService = requireText(backendService, "backendService");
        if (backendConnections < 1) throw new IllegalArgumentException("backendConnections must be positive");
        if (loginCommand < 1) throw new IllegalArgumentException("loginCommand must be positive");
        requirePositive(ratePps, "ratePps");
        requirePositive(rateBurst, "rateBurst");
        if (ddosMaxConnectionsPerIp < 1) throw new IllegalArgumentException("ddosMaxConnectionsPerIp must be positive");
        requirePositive(ddosPpsPerIp, "ddosPpsPerIp");
        requirePositive(ddosBurstPerIp, "ddosBurstPerIp");
        registryType = requireText(registryType, "registryType");
        registryEndpoints = requireText(registryEndpoints, "registryEndpoints");
        if (rpcAuthToken != null && rpcAuthToken.isBlank()) rpcAuthToken = null;
    }

    public static GatewayConfig load(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        Path file = Path.of("config", "application.properties");
        if (Files.isRegularFile(file)) {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
            catch (IOException error) { throw new IllegalStateException("cannot read " + file, error); }
            properties.forEach((key, value) -> values.put(key.toString(), value.toString()));
        }
        System.getenv().forEach((key, value) -> {
            if (key.startsWith("GAME_")) values.put(key.toLowerCase(Locale.ROOT).replace('_', '.'), value);
        });
        System.getProperties().forEach((key, value) -> {
            String name = key.toString();
            if (name.startsWith("game.")) values.put(name, value.toString());
        });
        if (args != null) for (String arg : args) {
            if (arg == null || !arg.startsWith("--") || !arg.contains("=")) continue;
            int separator = arg.indexOf('=');
            values.put(arg.substring(2, separator), arg.substring(separator + 1));
        }
        return from(values);
    }

    public static GatewayConfig from(Map<String, String> values) {
        int serverId = integer(values, "game.gateway.server-id", 1);
        return new GatewayConfig(
                integer(values, "game.gateway.tcp.port", 9000),
                integer(values, "game.gateway.ws.port", 9001),
                values.getOrDefault("game.gateway.ws.path", "/ws"),
                integer(values, "game.gateway.reader.idle.seconds", 180),
                serverId,
                values.getOrDefault("game.gateway.service", "game-gateway"),
                values.getOrDefault("game.gateway.instance-id", "gateway-" + serverId),
                values.getOrDefault("game.gateway.advertise-address", "127.0.0.1"),
                values.getOrDefault("game.gateway.backend.service", "game-dev"),
                integer(values, "game.gateway.backend.connections", 4),
                integer(values, "game.gateway.login.command", 1000),
                decimal(values, "game.gateway.rate.pps", 50),
                decimal(values, "game.gateway.rate.burst", 100),
                integer(values, "game.gateway.ddos.max-connections-per-ip", 100),
                decimal(values, "game.gateway.ddos.pps-per-ip", 500),
                decimal(values, "game.gateway.ddos.burst-per-ip", 1000),
                values.getOrDefault("game.registry.type", "memory"),
                values.getOrDefault("game.registry.endpoints", "local"),
                values.get("game.gateway.rpc.auth-token"));
    }

    public boolean webSocketEnabled() { return wsPort > 0; }
    public int getTcpPort() { return tcpPort; }
    public int getWsPort() { return wsPort; }
    public boolean isWebSocketEnabled() { return webSocketEnabled(); }
    public String getWsPath() { return wsPath; }
    public int getReaderIdleSeconds() { return readerIdleSeconds; }
    public int getServerId() { return serverId; }
    public String getServiceName() { return serviceName; }
    public String getInstanceId() { return instanceId; }
    public String getAdvertiseAddress() { return advertiseAddress; }
    public String getBackendService() { return backendService; }
    public int getBackendConnections() { return backendConnections; }
    public int getLoginCommand() { return loginCommand; }
    public double getRatePps() { return ratePps; }
    public double getRateBurst() { return rateBurst; }
    public int getDdosMaxConnPerIp() { return ddosMaxConnectionsPerIp; }
    public double getDdosPpsPerIp() { return ddosPpsPerIp; }
    public double getDdosBurstPerIp() { return ddosBurstPerIp; }
    public String getRegistryType() { return registryType; }
    public String getRegistryEndpoints() { return registryEndpoints; }

    private static int integer(Map<String, String> values, String key, int fallback) {
        String value = values.get(key);
        return value == null ? fallback : Integer.parseInt(value.trim());
    }
    private static double decimal(Map<String, String> values, String key, double fallback) {
        String value = values.get(key);
        return value == null ? fallback : Double.parseDouble(value.trim());
    }
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
    private static void requirePort(int value, boolean zeroAllowed, String name) {
        if (value < (zeroAllowed ? 0 : 1) || value > 65_535) throw new IllegalArgumentException(name + " is out of range");
    }
    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
