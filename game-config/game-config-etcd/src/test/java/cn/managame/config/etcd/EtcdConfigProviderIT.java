package cn.managame.config.etcd;

import cn.managame.config.ConfigCenter;
import cn.managame.config.ConfigFactory;
import cn.managame.config.ConfigOptions;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.PutOption;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class EtcdConfigProviderIT {
    @Container
    static final GenericContainer<?> ETCD = new GenericContainer<>(
            DockerImageName.parse("quay.io/coreos/etcd:v3.6.11"))
            .withExposedPorts(2379)
            .withCommand("/usr/local/bin/etcd", "--name=config-test", "--data-dir=/etcd-data",
                    "--listen-client-urls=http://0.0.0.0:2379",
                    "--advertise-client-urls=http://0.0.0.0:2379",
                    "--listen-peer-urls=http://0.0.0.0:2380",
                    "--initial-advertise-peer-urls=http://0.0.0.0:2380",
                    "--initial-cluster=config-test=http://0.0.0.0:2380")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(2));

    @Test void publishesOneAtomicSnapshotForEtcdTransaction() throws Exception {
        String endpoint = "http://" + ETCD.getHost() + ":" + ETCD.getMappedPort(2379);
        try (Client client = Client.builder().endpoints(endpoint).build()) {
            putTransaction(client, "port=7000\nname=base", "name=override");
            try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("etcd")
                    .endpoint(endpoint).resource("/config/base").resource("/config/override").build())) {
                assertEquals("7000", center.snapshot().get("port"));
                assertEquals("override", center.snapshot().get("name"));
                CountDownLatch changed = new CountDownLatch(1);
                AtomicInteger notifications = new AtomicInteger();
                AtomicReference<String> incoherent = new AtomicReference<>();
                center.listen(event -> {
                    notifications.incrementAndGet();
                    if ("8000".equals(event.current().get("port"))
                            && "latest".equals(event.current().get("name"))) {
                        changed.countDown();
                    } else {
                        incoherent.set(event.current().values().toString());
                    }
                });

                putTransaction(client, "port=8000\nname=base", "name=latest");

                assertTrue(changed.await(10, TimeUnit.SECONDS));
                assertEquals("8000", center.snapshot().get("port"));
                assertEquals("latest", center.snapshot().get("name"));
                assertNull(incoherent.get());
                assertEquals(1, notifications.get());
            }
        }
    }

    private static void putTransaction(Client client, String base, String override) throws Exception {
        client.getKVClient().txn().Then(
                Op.put(bytes("/config/base"), bytes(base), PutOption.DEFAULT),
                Op.put(bytes("/config/override"), bytes(override), PutOption.DEFAULT)
        ).commit().get(5, TimeUnit.SECONDS);
    }

    private static ByteSequence bytes(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }
}
