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

/** Immutable gateway settings grouped by responsibility. Every group has usable defaults. */
public record GatewayConfig(Transport transport, Identity identity, Backend backend,
                            Limits limits, Registry registry) {
    public GatewayConfig() {
        this(Transport.defaults(), Identity.defaults(), Backend.defaults(),
                Limits.defaults(), Registry.defaults());
    }

    public GatewayConfig(String backendService) {
        this(Transport.defaults(), Identity.defaults(), Backend.defaults().withService(backendService),
                Limits.defaults(), Registry.defaults());
    }

    public GatewayConfig {
        transport = transport == null ? Transport.defaults() : transport;
        identity = identity == null ? Identity.defaults() : identity;
        backend = backend == null ? Backend.defaults() : backend;
        limits = limits == null ? Limits.defaults() : limits;
        registry = registry == null ? Registry.defaults() : registry;
    }

    /** Starts with no required arguments; override only what the deployment needs. */
    public static GatewayConfig defaults() { return new GatewayConfig(); }

    /** Minimal programmatic configuration for the common single-backend deployment. */
    public static GatewayConfig forBackend(String serviceName) { return new GatewayConfig(serviceName); }

    public static GatewayConfig load(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        Path file = Path.of("config", "application.properties");
        if (Files.isRegularFile(file)) {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException error) {
                throw new IllegalStateException("cannot read " + file, error);
            }
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

    /** Reads a partial property map; omitted values always use defaults. */
    public static GatewayConfig from(Map<String, String> values) {
        int serverId = integer(values, "game.gateway.server-id", 1);
        return new GatewayConfig(
                new Transport(
                        integer(values, "game.gateway.tcp.port", 9000),
                        integer(values, "game.gateway.ws.port", 9001),
                        values.getOrDefault("game.gateway.ws.path", "/ws"),
                        integer(values, "game.gateway.reader.idle.seconds", 180)),
                new Identity(
                        serverId,
                        values.getOrDefault("game.gateway.service", "game-gateway"),
                        values.getOrDefault("game.gateway.instance-id", "gateway-" + serverId),
                        values.getOrDefault("game.gateway.advertise-address", "127.0.0.1")),
                new Backend(
                        values.getOrDefault("game.gateway.backend.service", "game-dev"),
                        values.getOrDefault("game.gateway.backend.routes", ""),
                        integer(values, "game.gateway.backend.connections", 4),
                        integer(values, "game.gateway.login.command", 1000),
                        values.get("game.gateway.rpc.auth-token")),
                new Limits(
                        decimal(values, "game.gateway.rate.pps", 50),
                        decimal(values, "game.gateway.rate.burst", 100),
                        integer(values, "game.gateway.ddos.max-connections-per-ip", 100),
                        decimal(values, "game.gateway.ddos.pps-per-ip", 500),
                        decimal(values, "game.gateway.ddos.burst-per-ip", 1000)),
                new Registry(
                        values.getOrDefault("game.registry.type", "memory"),
                        values.getOrDefault("game.registry.endpoints", "local")));
    }

    public record Transport(int tcpPort, int webSocketPort, String webSocketPath,
                            int readerIdleSeconds) {
        public Transport {
            requirePort(tcpPort, false, "tcpPort");
            requirePort(webSocketPort, true, "webSocketPort");
            if (webSocketPath == null || webSocketPath.isBlank()) webSocketPath = "/ws";
            if (!webSocketPath.startsWith("/")) webSocketPath = '/' + webSocketPath;
            if (readerIdleSeconds < 0) {
                throw new IllegalArgumentException("readerIdleSeconds must be non-negative");
            }
        }

        public static Transport defaults() { return new Transport(9000, 9001, "/ws", 180); }
        public boolean webSocketEnabled() { return webSocketPort > 0; }
    }

    public record Identity(int serverId, String serviceName, String instanceId, String advertiseAddress) {
        public Identity {
            if (serverId < 0) throw new IllegalArgumentException("serverId must be non-negative");
            serviceName = requireText(serviceName, "serviceName");
            instanceId = requireText(instanceId, "instanceId");
            advertiseAddress = requireText(advertiseAddress, "advertiseAddress");
        }

        public static Identity defaults() {
            return new Identity(1, "game-gateway", "gateway-1", "127.0.0.1");
        }
    }

    public record Backend(String service, String routes, int connections, int loginCommand,
                          String rpcAuthToken) {
        public Backend {
            service = requireText(service, "backend service");
            if (routes == null) routes = "";
            if (connections < 1) throw new IllegalArgumentException("backend connections must be positive");
            if (loginCommand < 1) throw new IllegalArgumentException("loginCommand must be positive");
            if (rpcAuthToken != null && rpcAuthToken.isBlank()) rpcAuthToken = null;
        }

        public static Backend defaults() { return new Backend("game-dev", "", 4, 1000, null); }

        public Backend withService(String serviceName) {
            return new Backend(serviceName, routes, connections, loginCommand, rpcAuthToken);
        }
    }

    public record Limits(double sessionPps, double sessionBurst, int maxConnectionsPerIp,
                         double ipPps, double ipBurst) {
        public Limits {
            requirePositive(sessionPps, "sessionPps");
            requirePositive(sessionBurst, "sessionBurst");
            if (maxConnectionsPerIp < 1) {
                throw new IllegalArgumentException("maxConnectionsPerIp must be positive");
            }
            requirePositive(ipPps, "ipPps");
            requirePositive(ipBurst, "ipBurst");
        }

        public static Limits defaults() { return new Limits(50, 100, 100, 500, 1000); }
    }

    public record Registry(String type, String endpoints) {
        public Registry {
            type = requireText(type, "registry type");
            endpoints = requireText(endpoints, "registry endpoints");
        }

        public static Registry defaults() { return new Registry("memory", "local"); }
    }

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
        if (value < (zeroAllowed ? 0 : 1) || value > 65_535) {
            throw new IllegalArgumentException(name + " is out of range");
        }
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
