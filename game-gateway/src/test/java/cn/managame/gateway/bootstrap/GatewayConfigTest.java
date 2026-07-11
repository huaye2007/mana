package cn.managame.gateway.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GatewayConfigTest {
    @Test void suppliesProductionSafeDefaultsAndNormalizesPath() {
        GatewayConfig config = GatewayConfig.from(Map.of("game.gateway.ws.path", "socket"));
        assertEquals(9000, config.tcpPort());
        assertEquals("/socket", config.wsPath());
        assertEquals("gateway-1", config.instanceId());
        assertTrue(config.webSocketEnabled());
    }

    @Test void zeroDisablesWebSocketAndInvalidRatesFailFast() {
        assertFalse(GatewayConfig.from(Map.of("game.gateway.ws.port", "0")).webSocketEnabled());
        assertThrows(IllegalArgumentException.class,
                () -> GatewayConfig.from(Map.of("game.gateway.rate.pps", "0")));
    }

    @Test void exposesMultiServiceRouteSpecification() {
        GatewayConfig config = GatewayConfig.from(Map.of(
                "game.gateway.backend.service", "scene-service",
                "game.gateway.backend.routes", "1000=auth-service,2000-2999=chat-service"));
        assertEquals("scene-service", config.backendService());
        assertEquals("1000=auth-service,2000-2999=chat-service", config.backendRoutes());
    }
}
