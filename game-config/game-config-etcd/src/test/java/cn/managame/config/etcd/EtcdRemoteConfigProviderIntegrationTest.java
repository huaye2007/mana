package cn.managame.config.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EtcdRemoteConfigProviderIntegrationTest {
    @Test
    void shouldLoadAndWatchRealEtcdConfig() throws Exception {
        assumeTrue(enabled(), "Set GAME_CONFIG_INTEGRATION_ETCD=true to run Etcd integration tests");

        String endpoints = setting(
                "game.config.etcd.endpoints",
                "GAME_CONFIG_ETCD_ENDPOINTS",
                "http://127.0.0.1:2379");
        String dataId = setting(
                "game.config.etcd.dataId",
                "GAME_CONFIG_ETCD_DATA_ID",
                "/game-config/integration/" + System.nanoTime());

        Properties properties = new Properties();
        properties.setProperty("endpoints", endpoints);
        properties.setProperty("dataId", dataId);
        properties.setProperty("timeoutMs", setting(
                "game.config.etcd.timeoutMs",
                "GAME_CONFIG_ETCD_TIMEOUT_MS",
                "3000"));

        Client client = Client.builder().endpoints(splitEndpoints(endpoints)).build();
        EtcdRemoteConfigProvider provider = new EtcdRemoteConfigProvider();
        try {
            put(client, dataId, "value=one\n");
            assertEquals("one", provider.load(properties).get("value"));

            CountDownLatch pushed = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            AtomicReference<Map<String, String>> latest = new AtomicReference<>();
            provider.subscribe(properties, config -> {
                if (callbacks.incrementAndGet() > 1 && "two".equals(config.get("value"))) {
                    latest.set(config);
                    pushed.countDown();
                }
            });

            put(client, dataId, "value=two\n");
            assertTrue(pushed.await(10, TimeUnit.SECONDS));
            assertEquals("two", latest.get().get("value"));
        } finally {
            provider.close();
            delete(client, dataId);
            client.close();
        }
    }

    private void put(Client client, String key, String content) throws Exception {
        client.getKVClient()
                .put(ByteSequence.from(key, StandardCharsets.UTF_8),
                        ByteSequence.from(content, StandardCharsets.UTF_8))
                .get(3, TimeUnit.SECONDS);
    }

    private void delete(Client client, String key) throws Exception {
        client.getKVClient()
                .delete(ByteSequence.from(key, StandardCharsets.UTF_8))
                .get(3, TimeUnit.SECONDS);
    }

    private String[] splitEndpoints(String endpoints) {
        return java.util.Arrays.stream(endpoints.split(","))
                .map(String::trim)
                .filter(endpoint -> !endpoint.isEmpty())
                .toArray(String[]::new);
    }

    private boolean enabled() {
        return Boolean.parseBoolean(setting(
                "game.config.integration.etcd",
                "GAME_CONFIG_INTEGRATION_ETCD",
                "false"));
    }

    private String setting(String propertyName, String envName, String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue.trim();
        }
        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        return defaultValue;
    }
}
