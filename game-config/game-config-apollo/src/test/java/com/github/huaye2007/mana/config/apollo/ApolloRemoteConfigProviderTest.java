package com.github.huaye2007.mana.config.apollo;

import com.github.huaye2007.mana.config.exception.ConfigOperationException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApolloRemoteConfigProviderTest {
    @Test
    void shouldRejectMissingAppId() {
        ApolloRemoteConfigProvider provider = new ApolloRemoteConfigProvider(new FakeApolloTransport());

        ConfigOperationException error = assertThrows(
                ConfigOperationException.class,
                () -> provider.load(null));

        assertEquals("Apollo config requires appId", error.getMessage());
    }

    @Test
    void shouldLoadAndMergeNamespacesInDeclaredOrder() throws Exception {
        FakeApolloTransport transport = new FakeApolloTransport();
        transport.putConfig("base", """
                {"releaseKey":"r1","configurations":{"shared":"base","base.only":"true"}}
                """);
        transport.putConfig("room", """
                {"releaseKey":"r2","configurations":{"shared":"room","room.only":"true"}}
                """);
        ApolloRemoteConfigProvider provider = new ApolloRemoteConfigProvider(transport);

        Map<String, String> config = provider.load(properties("base,room"));

        assertEquals("room", config.get("shared"));
        assertEquals("true", config.get("base.only"));
        assertEquals("true", config.get("room.only"));
    }

    @Test
    void shouldSignRequestsWhenAccessKeySecretConfigured() throws Exception {
        FakeApolloTransport transport = new FakeApolloTransport();
        transport.putConfig("application", """
                {"releaseKey":"r1","configurations":{"enabled":"true"}}
                """);
        ApolloRemoteConfigProvider provider = new ApolloRemoteConfigProvider(transport);
        Properties properties = properties("application");
        properties.setProperty("accessKeySecret", "test-secret");

        Map<String, String> config = provider.load(properties);

        assertEquals("true", config.get("enabled"));
        assertTrue(transport.lastHeaders.get("Authorization").startsWith("Apollo game:"));
        assertTrue(transport.lastHeaders.containsKey("Timestamp"));
    }

    @Test
    void shouldTreatEmptyApolloConfigurationsAsDeletedKeys() throws Exception {
        FakeApolloTransport transport = new FakeApolloTransport();
        transport.putConfig("application", """
                {"releaseKey":"r1","configurations":{}}
                """);
        ApolloRemoteConfigProvider provider = new ApolloRemoteConfigProvider(transport);

        Map<String, String> config = provider.load(properties("application"));

        assertTrue(config.isEmpty());
    }

    @Test
    void shouldLongPollAndPushMergedSnapshotWhenNamespaceChanges() throws Exception {
        FakeApolloTransport transport = new FakeApolloTransport();
        transport.putConfig("base", """
                {"releaseKey":"r1","configurations":{"shared":"base","base.only":"true"}}
                """);
        transport.putConfig("room", """
                {"releaseKey":"r2","configurations":{"shared":"room","room.only":"true"}}
                """);
        ApolloRemoteConfigProvider provider = new ApolloRemoteConfigProvider(transport);
        CountDownLatch pushed = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<Map<String, String>> latest = new AtomicReference<>();

        provider.subscribe(properties("base,room"), config -> {
            if (callbacks.incrementAndGet() > 1) {
                latest.set(config);
                pushed.countDown();
            }
        });

        assertTrue(pushed.await(2, TimeUnit.SECONDS));
        provider.close();
        assertEquals("room", latest.get().get("shared"));
        assertEquals("false", latest.get().get("base.only"));
        assertEquals("true", latest.get().get("room.only"));
    }

    private Properties properties(String namespaces) {
        Properties properties = new Properties();
        properties.setProperty("configServiceUrl", "http://apollo.test");
        properties.setProperty("appId", "game");
        properties.setProperty("cluster", "default");
        properties.setProperty("namespaces", namespaces);
        properties.setProperty("longPollTimeoutMs", "50");
        properties.setProperty("retryDelayMs", "1");
        return properties;
    }

    private static final class FakeApolloTransport implements ApolloRemoteConfigProvider.ApolloHttpTransport {
        private final Map<String, String> configs = new HashMap<>();
        private final AtomicInteger notificationCalls = new AtomicInteger();
        private volatile Map<String, String> lastHeaders = Map.of();

        void putConfig(String namespace, String body) {
            configs.put(namespace, body);
        }

        @Override
        public ApolloRemoteConfigProvider.ApolloHttpResult get(
                URI uri,
                long timeoutMillis,
                Map<String, String> headers) throws Exception {
            lastHeaders = headers;
            String path = uri.getPath();
            if (path.startsWith("/configs/")) {
                String namespace = path.substring(path.lastIndexOf('/') + 1);
                return new ApolloRemoteConfigProvider.ApolloHttpResult(
                        200,
                        configs.getOrDefault(namespace, "{}"),
                        Map.of());
            }
            if (path.equals("/notifications/v2") && notificationCalls.incrementAndGet() == 1) {
                configs.put("base", """
                        {"releaseKey":"r3","configurations":{"shared":"base2","base.only":"false"}}
                        """);
                return new ApolloRemoteConfigProvider.ApolloHttpResult(
                        200,
                        """
                                [{"namespaceName":"base","notificationId":2}]
                                """,
                        Map.of());
            }
            Thread.sleep(Math.min(timeoutMillis, 20));
            return new ApolloRemoteConfigProvider.ApolloHttpResult(304, "", Map.of());
        }
    }
}
