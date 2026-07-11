package cn.managame.gateway.router;

import cn.managame.registry.api.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConsistentHashRouterTest {
    private static ServiceInstance instance(String id) {
        return ServiceInstance.builder().name("game").id(id).address("127.0.0.1").port(9000).build();
    }

    @Test void selectionIsStableAndRemovalOnlyMovesAffectedKeys() {
        ConsistentHashRouter router = new ConsistentHashRouter();
        router.refresh(List.of(instance("a"), instance("b"), instance("c")));
        String[] before = new String[2000];
        for (int i = 0; i < before.length; i++) before[i] = router.select(i).getKey();
        router.refresh(List.of(instance("a"), instance("b")));
        int moved = 0;
        for (int i = 0; i < before.length; i++) {
            String after = router.select(i).getKey();
            if (!before[i].equals(after)) { moved++; assertEquals("c", before[i]); }
        }
        assertTrue(moved > 0 && moved < before.length);
    }

    @Test void ignoresUnhealthyInstances() {
        ServiceInstance unhealthy = ServiceInstance.builder().name("game").id("bad").address("127.0.0.1").port(1).healthy(false).build();
        ConsistentHashRouter router = new ConsistentHashRouter();
        router.refresh(List.of(unhealthy));
        assertNull(router.select(1));
    }
}
