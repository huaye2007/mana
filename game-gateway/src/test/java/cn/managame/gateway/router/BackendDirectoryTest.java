package cn.managame.gateway.router;

import cn.managame.gateway.session.GatewaySession;
import cn.managame.gateway.support.FakeConnection;
import cn.managame.registry.api.ServiceInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackendDirectoryTest {
    @Test void keepsServicesIsolatedAndDropsRemovedStickyInstance() {
        BackendDirectory directory = new BackendDirectory();
        ServiceInstance auth = instance("auth", "a1");
        ServiceInstance scene1 = instance("scene", "s1");
        ServiceInstance scene2 = instance("scene", "s2");
        directory.upsert(auth);
        directory.upsert(scene1);
        directory.upsert(scene2);
        GatewaySession session = new GatewaySession(7, new FakeConnection(7, "127.0.0.1"), "127.0.0.1");

        assertSame(auth, directory.resolve("auth", session));
        ServiceInstance selected = directory.resolve("scene", session);
        directory.remove(selected);

        assertEquals(2, directory.serviceCount());
        assertEquals(1, directory.instanceCount("scene"));
        assertNotEquals(selected, directory.resolve("scene", session));
    }

    private static ServiceInstance instance(String service, String id) {
        return ServiceInstance.builder().name(service).id(id).address("127.0.0.1").port(9000).build();
    }
}
