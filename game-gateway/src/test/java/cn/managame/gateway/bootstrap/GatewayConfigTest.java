package cn.managame.gateway.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GatewayConfigTest {
    @Test void suppliesProductionSafeDefaultsAndNormalizesPath() {
        GatewayConfig config = GatewayConfig.from(Map.of("game.gateway.ws.path", "socket"));
        assertEquals(9000, config.transport().tcpPort());
        assertEquals("/socket", config.transport().webSocketPath());
        assertEquals("gateway-1", config.identity().instanceId());
        assertTrue(config.transport().webSocketEnabled());
    }

    @Test void zeroDisablesWebSocketAndInvalidRatesFailFast() {
        assertFalse(GatewayConfig.from(Map.of("game.gateway.ws.port", "0"))
                .transport().webSocketEnabled());
        assertThrows(IllegalArgumentException.class,
                () -> GatewayConfig.from(Map.of("game.gateway.rate.pps", "0")));
    }

    @Test void exposesMultiServiceRouteSpecification() {
        GatewayConfig config = GatewayConfig.from(Map.of(
                "game.gateway.backend.service", "scene-service",
                "game.gateway.backend.routes", "1000=auth-service,2000-2999=chat-service"));
        assertEquals("scene-service", config.backend().service());
        assertEquals("1000=auth-service,2000-2999=chat-service", config.backend().routes());
    }

    @Test void supportsZeroArgumentDefaultsAndSingleBackendShortcut() {
        GatewayConfig defaults = new GatewayConfig();
        assertEquals("game-dev", defaults.backend().service());
        assertEquals("memory", defaults.registry().type());

        GatewayConfig configured = new GatewayConfig("scene-service");
        assertEquals("scene-service", configured.backend().service());
        assertEquals(GatewayConfig.Limits.defaults(), configured.limits());
        assertEquals(defaults, GatewayConfig.defaults());
        assertEquals(configured, GatewayConfig.forBackend("scene-service"));
    }

    @Test void keepsAdvancedPropertyKeysCompatible() {
        GatewayConfig config = GatewayConfig.from(Map.of(
                "game.gateway.service", "edge-gateway",
                "game.gateway.instance-id", "edge-2",
                "game.gateway.backend.connections", "8",
                "game.gateway.rpc.auth-token", "secret",
                "game.registry.endpoints", "registry:8848"));
        assertEquals("edge-gateway", config.identity().serviceName());
        assertEquals("edge-2", config.identity().instanceId());
        assertEquals(8, config.backend().connections());
        assertEquals("secret", config.backend().rpcAuthToken());
        assertEquals("registry:8848", config.registry().endpoints());
    }
}
