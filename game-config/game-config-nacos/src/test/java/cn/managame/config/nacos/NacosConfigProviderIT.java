package cn.managame.config.nacos;

import cn.managame.config.ConfigCenter;
import cn.managame.config.ConfigFactory;
import cn.managame.config.ConfigOptions;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class NacosConfigProviderIT {
    private static final int CLIENT_PORT = findPortPair();

    @Container
    static final NacosContainer NACOS = new NacosContainer(CLIENT_PORT);

    @Test void retainsLastSnapshotUntilAllNacosResourcesShareRevision() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("serverAddr", "127.0.0.1:" + CLIENT_PORT);
        ConfigService publisher = NacosFactory.createConfigService(properties);
        try {
            String initialBase = "_revision=1\nport=7000\nname=base";
            String initialOverride = "_revision=1\nname=override";
            assertTrue(publisher.publishConfig("base", "GAME", initialBase));
            assertTrue(publisher.publishConfig("override", "GAME", initialOverride));
            ConfigService verifier = NacosFactory.createConfigService(properties);
            try {
                awaitPublication(verifier, initialBase, initialOverride);
            } finally {
                verifier.shutDown();
            }
            try (ConfigCenter center = ConfigFactory.open(ConfigOptions.builder("nacos")
                    .endpoint("127.0.0.1:" + CLIENT_PORT)
                    .resource("GAME:base").resource("GAME:override").build())) {
                CountDownLatch changed = new CountDownLatch(1);
                CountDownLatch anyChange = new CountDownLatch(1);
                center.listen(event -> {
                    anyChange.countDown();
                    if ("8000".equals(event.current().get("port"))
                            && "latest".equals(event.current().get("name"))) changed.countDown();
                });

                assertTrue(publisher.publishConfig("base", "GAME", "_revision=2\nport=8000\nname=base"));
                assertFalse(anyChange.await(2, TimeUnit.SECONDS));
                assertEquals("7000", center.snapshot().get("port"));
                assertTrue(publisher.publishConfig("override", "GAME", "_revision=2\nname=latest"));

                assertTrue(changed.await(15, TimeUnit.SECONDS));
                assertEquals("8000", center.snapshot().get("port"));
                assertEquals("latest", center.snapshot().get("name"));
            }
        } finally {
            publisher.shutDown();
        }
    }

    private static void awaitPublication(ConfigService service, String expectedBase,
                                         String expectedOverride) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            String base = service.getConfig("base", "GAME", 1000);
            String override = service.getConfig("override", "GAME", 1000);
            if (expectedBase.equals(base) && expectedOverride.equals(override)) return;
            Thread.sleep(100);
        }
        throw new AssertionError("initial Nacos publications did not become readable");
    }

    private static int findPortPair() {
        for (int base = 18000; base < 50000; base++) {
            try (ServerSocket first = new ServerSocket(base); ServerSocket second = new ServerSocket(base + 1000)) {
                return base;
            } catch (IOException ignored) {
                // Try another adjacent Nacos HTTP/gRPC host-port pair.
            }
        }
        throw new IllegalStateException("cannot find ports for Nacos integration test");
    }

    private static final class NacosContainer extends GenericContainer<NacosContainer> {
        private NacosContainer(int clientPort) {
            super(DockerImageName.parse("nacos/nacos-server:v3.2.0"));
            addFixedExposedPort(clientPort, 8848);
            addFixedExposedPort(clientPort + 1000, 9848);
            withEnv("MODE", "standalone");
            withEnv("NACOS_AUTH_ENABLE", "false");
            withEnv("NACOS_AUTH_TOKEN", "VGhpc0lzQVN1ZmZpY2llbnRseUxvbmdUZXN0VG9rZW4=");
            withEnv("NACOS_AUTH_IDENTITY_KEY", "config-test-key");
            withEnv("NACOS_AUTH_IDENTITY_VALUE", "config-test-value");
            waitingFor(Wait.forHttp("/nacos/v3/admin/core/state/readiness")
                    .forPort(8848).forStatusCode(200));
            withStartupTimeout(Duration.ofMinutes(3));
        }
    }
}
