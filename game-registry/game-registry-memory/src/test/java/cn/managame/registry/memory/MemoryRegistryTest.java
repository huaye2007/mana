package cn.managame.registry.memory;

import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryRegistryTest {
    @Test
    void sharesNamespaceAndPublishesLifecycleEvents() throws Exception {
        String namespace = UUID.randomUUID().toString();
        ServiceInstance first = instance("one", 9001);
        ServiceInstance updated = instance("one", 9002);
        List<ServiceInstanceEvent> events = new ArrayList<>();

        try (MemoryRegistry publisher = new MemoryRegistry(namespace);
             MemoryRegistry subscriber = new MemoryRegistry(namespace)) {
            publisher.register(first);
            try (AutoCloseable ignored = subscriber.watchService("game", events::add)) {
                publisher.register(updated);
                publisher.deregister(updated);
            }
        }

        assertEquals(List.of(DiscoveryEventType.ADDED, DiscoveryEventType.UPDATED, DiscoveryEventType.REMOVED),
                events.stream().map(ServiceInstanceEvent::getType).toList());
        assertEquals(9002, events.get(1).getInstance().getPort());
    }

    @Test
    void closeOnlyRemovesRegistrationsStillOwnedByClient() {
        String namespace = UUID.randomUUID().toString();
        MemoryRegistry oldOwner = new MemoryRegistry(namespace);
        try (oldOwner; MemoryRegistry newOwner = new MemoryRegistry(namespace);
             MemoryRegistry observer = new MemoryRegistry(namespace)) {
            oldOwner.register(instance("shared", 9001));
            newOwner.register(instance("shared", 9002));
            oldOwner.close();
            assertEquals(9002, observer.getInstances("game").getFirst().getPort());
        }
    }

    @Test
    void rejectsOperationsAfterClose() {
        MemoryRegistry registry = new MemoryRegistry(UUID.randomUUID().toString());
        registry.close();
        assertThrows(IllegalStateException.class, () -> registry.getInstances("game"));
    }

    @Test
    void closeRacingRegistrationsNeverLeavesGhostInstances() throws Exception {
        for (int round = 0; round < 25; round++) {
            String namespace = UUID.randomUUID().toString();
            MemoryRegistry registry = new MemoryRegistry(namespace);
            try (MemoryRegistry observer = new MemoryRegistry(namespace)) {
                int workers = 32;
                CountDownLatch ready = new CountDownLatch(workers + 1);
                CountDownLatch start = new CountDownLatch(1);
                List<Thread> threads = new ArrayList<>();
                for (int index = 0; index < workers; index++) {
                    int id = index;
                    threads.add(Thread.startVirtualThread(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            registry.register(instance("node-" + id, 10_000 + id));
                        } catch (IllegalStateException ignored) {
                            // close linearized before this registration
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
                }
                Thread closer = Thread.startVirtualThread(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        registry.close();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                ready.await();
                start.countDown();
                for (Thread thread : threads) thread.join();
                closer.join();

                assertEquals(List.of(), observer.getInstances("game"));
            } finally {
                registry.close();
            }
        }
    }

    private static ServiceInstance instance(String id, int port) {
        return ServiceInstance.builder().name("game").id(id).address("127.0.0.1").port(port).build();
    }
}
