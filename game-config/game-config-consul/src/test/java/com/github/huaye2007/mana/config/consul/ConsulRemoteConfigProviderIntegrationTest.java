package com.github.huaye2007.mana.config.consul;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConsulRemoteConfigProviderIntegrationTest {

    @Test
    void shouldLoadAndWatchRealConsulKv() throws Exception {
        assumeTrue(enabled(), "Set GAME_CONFIG_INTEGRATION_CONSUL=true to run Consul integration tests");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        String endpoint = setting(
                "game.config.consul.endpoint",
                "GAME_CONFIG_CONSUL_ENDPOINT",
                "http://127.0.0.1:8500");
        String token = setting("game.config.consul.token", "GAME_CONFIG_CONSUL_TOKEN", null);
        String key = setting(
                "game.config.consul.key",
                "GAME_CONFIG_CONSUL_KEY",
                "game-config/integration/" + System.nanoTime());
        ConsulRemoteConfigProvider provider = new ConsulRemoteConfigProvider();
        try {
            put(client, endpoint, key, "value=one\n", token);

            Properties properties = new Properties();
            properties.setProperty("endpoint", endpoint);
            properties.setProperty("key", key);
            properties.setProperty("waitSeconds", "1");
            properties.setProperty("retryDelayMs", "50");
            if (token != null && !token.isBlank()) {
                properties.setProperty("token", token);
            }

            assertEquals("one", provider.load(properties).get("value"));

            CountDownLatch pushed = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            AtomicReference<Map<String, String>> latest = new AtomicReference<>();
            provider.subscribe(properties, config -> {
                if (callbacks.incrementAndGet() > 1) {
                    latest.set(config);
                    pushed.countDown();
                }
            });
            put(client, endpoint, key, "value=two\n", token);

            assertTrue(pushed.await(5, TimeUnit.SECONDS));
            assertEquals("two", latest.get().get("value"));
        } finally {
            provider.close();
            delete(client, endpoint, key, token);
        }
    }

    private void put(HttpClient client, String endpoint, String key, String content, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(kvUri(endpoint, key))
                .timeout(Duration.ofSeconds(3))
                .PUT(HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8));
        addToken(builder, token);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
    }

    private void delete(HttpClient client, String endpoint, String key, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(kvUri(endpoint, key))
                .timeout(Duration.ofSeconds(3))
                .DELETE();
        addToken(builder, token);
        client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
    }

    private URI kvUri(String endpoint, String key) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return URI.create(base + "/v1/kv/" + key);
    }

    private void addToken(HttpRequest.Builder builder, String token) {
        if (token != null && !token.isBlank()) {
            builder.header("X-Consul-Token", token);
        }
    }

    private boolean enabled() {
        return Boolean.parseBoolean(setting(
                "game.config.integration.consul",
                "GAME_CONFIG_INTEGRATION_CONSUL",
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
