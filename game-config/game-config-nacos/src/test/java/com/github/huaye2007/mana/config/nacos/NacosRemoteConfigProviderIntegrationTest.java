package com.github.huaye2007.mana.config.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NacosRemoteConfigProviderIntegrationTest {
    @Test
    void shouldLoadAndWatchRealNacosConfig() throws Exception {
        assumeTrue(enabled(), "Set GAME_CONFIG_INTEGRATION_NACOS=true to run Nacos integration tests");

        Properties properties = new Properties();
        properties.setProperty("serverAddr", setting(
                "game.config.nacos.serverAddr",
                "GAME_CONFIG_NACOS_SERVER_ADDR",
                "127.0.0.1:8848"));
        properties.setProperty("group", setting(
                "game.config.nacos.group",
                "GAME_CONFIG_NACOS_GROUP",
                "DEFAULT_GROUP"));
        properties.setProperty("dataId", setting(
                "game.config.nacos.dataId",
                "GAME_CONFIG_NACOS_DATA_ID",
                "game-config-integration-" + System.nanoTime() + ".properties"));
        properties.setProperty("timeoutMs", setting(
                "game.config.nacos.timeoutMs",
                "GAME_CONFIG_NACOS_TIMEOUT_MS",
                "3000"));
        maybeSet(properties, "namespace", setting("game.config.nacos.namespace", "GAME_CONFIG_NACOS_NAMESPACE", null));
        maybeSet(properties, "username", setting("game.config.nacos.username", "GAME_CONFIG_NACOS_USERNAME", null));
        maybeSet(properties, "password", setting("game.config.nacos.password", "GAME_CONFIG_NACOS_PASSWORD", null));

        ConfigService configService = NacosFactory.createConfigService(properties);
        NacosRemoteConfigProvider provider = new NacosRemoteConfigProvider();
        String dataId = properties.getProperty("dataId");
        String group = properties.getProperty("group");
        try {
            assertTrue(configService.publishConfig(dataId, group, "value=one\n"));
            assertEquals("one", awaitLoad(provider, properties, "value", "one"));

            CountDownLatch pushed = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            AtomicReference<Map<String, String>> latest = new AtomicReference<>();
            provider.subscribe(properties, config -> {
                if (callbacks.incrementAndGet() > 1 && "two".equals(config.get("value"))) {
                    latest.set(config);
                    pushed.countDown();
                }
            });

            assertTrue(configService.publishConfig(dataId, group, "value=two\n"));
            assertTrue(pushed.await(10, TimeUnit.SECONDS));
            assertEquals("two", latest.get().get("value"));
        } finally {
            provider.close();
            configService.removeConfig(dataId, group);
            configService.shutDown();
        }
    }

    private String awaitLoad(NacosRemoteConfigProvider provider,
                             Properties properties,
                             String key,
                             String expectedValue) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        String actual = null;
        while (System.currentTimeMillis() < deadline) {
            actual = provider.load(properties).get(key);
            if (expectedValue.equals(actual)) {
                return actual;
            }
            Thread.sleep(100L);
        }
        return actual;
    }

    private boolean enabled() {
        return Boolean.parseBoolean(setting(
                "game.config.integration.nacos",
                "GAME_CONFIG_INTEGRATION_NACOS",
                "false"));
    }

    private void maybeSet(Properties properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.setProperty(key, value);
        }
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
