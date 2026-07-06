package cn.managame.gateway.router;

import cn.managame.registry.api.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistentHashRouterTest {

    private static ServiceInstance instance(String id) {
        return ServiceInstance.builder().name("game-dev").id(id).address("127.0.0.1").port(9000).build();
    }

    @Test
    void emptyRingSelectsNull() {
        assertNull(new ConsistentHashRouter().select(123L));
    }

    @Test
    void selectionIsStableForSameKey() {
        ConsistentHashRouter router = new ConsistentHashRouter();
        router.refresh(List.of(instance("a"), instance("b"), instance("c")));

        ServiceInstance first = router.select(42L);
        assertNotNull(first);
        for (int i = 0; i < 100; i++) {
            assertEquals(first.getKey(), router.select(42L).getKey(), "同 key 每次应命中同实例");
        }
    }

    @Test
    void removingInstanceKeepsKeysOnSurvivors() {
        ConsistentHashRouter router = new ConsistentHashRouter();
        router.refresh(List.of(instance("a"), instance("b"), instance("c")));

        int keyCount = 2000;
        String[] before = new String[keyCount];
        for (int k = 0; k < keyCount; k++) {
            before[k] = router.select(k).getKey();
        }

        // 移除 c：一致性哈希只应重映射原本落在 c 上的 key，其余保持不变
        router.refresh(List.of(instance("a"), instance("b")));

        int moved = 0;
        for (int k = 0; k < keyCount; k++) {
            String now = router.select(k).getKey();
            assertTrue(now.equals("a") || now.equals("b"));
            if (!now.equals(before[k])) {
                moved++;
                assertEquals("c", before[k], "只有原本落在被移除实例上的 key 才应重映射");
            }
        }
        assertTrue(moved > 0 && moved < keyCount, "应有部分 key 重映射，但远不是全部");
    }

    @Test
    void distributesAcrossInstances() {
        ConsistentHashRouter router = new ConsistentHashRouter();
        router.refresh(List.of(instance("a"), instance("b"), instance("c")));

        int a = 0, b = 0, c = 0;
        for (int k = 0; k < 3000; k++) {
            switch (router.select(k).getKey()) {
                case "a" -> a++;
                case "b" -> b++;
                case "c" -> c++;
                default -> throw new AssertionError("unexpected instance");
            }
        }
        // 160 虚拟节点下三实例应各自拿到相当份额（宽松下界，避免偶发抖动误报）
        assertTrue(a > 300 && b > 300 && c > 300, "分布过于倾斜: a=" + a + " b=" + b + " c=" + c);
    }
}
