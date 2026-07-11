package cn.managame.gateway.router;

import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;
import cn.managame.gateway.support.FakeConnection;
import cn.managame.registry.api.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandBackendServiceResolverTest {
    @Test void resolvesExactAndRangeRoutesWithDefaultFallback() {
        CommandBackendServiceResolver resolver = CommandBackendServiceResolver.parse(
                "scene-service", "1000=auth-service,2000-2999=chat-service");
        GatewaySession session = new GatewaySession(new FakeConnection(7, "127.0.0.1"), "127.0.0.1");
        assertEquals("auth-service", resolver.resolve(session, packet(1000)));
        assertEquals("chat-service", resolver.resolve(session, packet(2500)));
        assertEquals("scene-service", resolver.resolve(session, packet(4000)));
        assertEquals(3, resolver.serviceNames().size());
    }

    @Test void rejectsOverlappingRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandBackendServiceResolver.parse("game", "1000-2000=a,1500-2500=b"));
    }

    @Test void keepsIndependentStickyBindingPerServiceType() {
        BackendDirectory directory = new BackendDirectory(ConsistentHashRouter::new);
        ServiceInstance auth = instance("auth", "a1");
        ServiceInstance scene = instance("scene", "s1");
        directory.service("auth").upsert(auth);
        directory.service("scene").upsert(scene);
        GatewaySession session = new GatewaySession(new FakeConnection(9, "127.0.0.1"), "127.0.0.1");

        assertEquals(auth, directory.resolve("auth", session));
        assertEquals(scene, directory.resolve("scene", session));
        assertEquals("a1", session.getBackendServiceId("auth"));
        assertEquals("s1", session.getBackendServiceId("scene"));
    }

    private static GatewayPacket packet(int command) { return GatewayPacket.of(command, 1, 0, new byte[0]); }
    private static ServiceInstance instance(String service, String id) {
        return ServiceInstance.builder().name(service).id(id).address("127.0.0.1").port(9000).build();
    }
}
