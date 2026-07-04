package cn.managame.config.consul;

import cn.managame.config.exception.ConfigOperationException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

class ConsulRemoteConfigProviderTest {
    @Test
    void shouldRejectMissingKeyOrPrefix() {
        ConsulRemoteConfigProvider provider = new ConsulRemoteConfigProvider(new FakeConsulTransport());

        ConfigOperationException error = assertThrows(
                ConfigOperationException.class,
                () -> provider.load(null));

        assertEquals("Consul config requires key, keys, or prefix", error.getMessage());
    }

    @Test
    void shouldLoadAndMergeKeysInDeclaredOrder() throws Exception {
        FakeConsulTransport transport = new FakeConsulTransport();
        transport.putKey("base", "shared=base\nbase.only=true\n", 10);
        transport.putKey("room", "shared=room\nroom.only=true\n", 20);
        ConsulRemoteConfigProvider provider = new ConsulRemoteConfigProvider(transport);

        Map<String, String> config = provider.load(properties("base,room"));

        assertEquals("room", config.get("shared"));
        assertEquals("true", config.get("base.only"));
        assertEquals("true", config.get("room.only"));
    }

    @Test
    void shouldLoadPrefixWithSingleConsulRequest() throws Exception {
        FakeConsulTransport transport = new FakeConsulTransport();
        transport.putPrefix("game/config", Map.of(
                "game/config/base", "shared=base\nbase.only=true\n",
                "game/config/room", "shared=room\nroom.only=true\n"), 30);
        ConsulRemoteConfigProvider provider = new ConsulRemoteConfigProvider(transport);
        Properties properties = properties("");
        properties.remove("keys");
        properties.setProperty("prefix", "game/config");

        Map<String, String> config = provider.load(properties);

        assertEquals("room", config.get("shared"));
        assertEquals("true", config.get("base.only"));
        assertEquals("true", config.get("room.only"));
        assertEquals(1, transport.prefixLoads);
    }

    @Test
    void shouldForwardConsulAclToken() throws Exception {
        FakeConsulTransport transport = new FakeConsulTransport();
        transport.putKey("base", "enabled=true\n", 10);
        ConsulRemoteConfigProvider provider = new ConsulRemoteConfigProvider(transport);
        Properties properties = properties("base");
        properties.setProperty("token", "token-1");

        Map<String, String> config = provider.load(properties);

        assertEquals("true", config.get("enabled"));
        assertEquals("token-1", transport.lastHeaders.get("X-Consul-Token"));
    }

    @Test
    void shouldRejectMoreWatchTargetsThanWatchThreads() throws Exception {
        FakeConsulTransport transport = new FakeConsulTransport();
        transport.putKey("a", "a=true\n", 10);
        transport.putKey("b", "b=true\n", 20);
        transport.putKey("c", "c=true\n", 30);
        ConsulRemoteConfigProvider provider = new ConsulRemoteConfigProvider(transport);
        Properties properties = properties("a,b,c");
        properties.setProperty("watchThreads", "2");

        ConfigOperationException error = assertThrows(
                ConfigOperationException.class,
                () -> provider.subscribe(properties, ignored -> { }));

        assertTrue(error.getMessage().contains("exceeds watchThreads"));
        provider.close();
    }

    @Test
    void shouldPublishDeletedKeyAsRemovedSnapshotEntry() throws Exception {
        FakeConsulTransport transport = new FakeConsulTransport();
        transport.putKey("base", "base.only=true\n", 10);
        transport.putKey("room", "room.only=true\n", 20);
        transport.deleteOnFirstBlocking("base", 11);
        ConsulRemoteConfigProvider provider = new ConsulRemoteConfigProvider(transport);
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
        assertEquals(null, latest.get().get("base.only"));
        assertEquals("true", latest.get().get("room.only"));
    }

    @Test
    void shouldUseBlockingQueryAndPushMergedSnapshotWhenKeyChanges() throws Exception {
        FakeConsulTransport transport = new FakeConsulTransport();
        transport.putKey("base", "shared=base\nbase.only=true\n", 10);
        transport.putKey("room", "shared=room\nroom.only=true\n", 20);
        ConsulRemoteConfigProvider provider = new ConsulRemoteConfigProvider(transport);
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

    private Properties properties(String keys) {
        Properties properties = new Properties();
        properties.setProperty("endpoint", "http://consul.test");
        properties.setProperty("keys", keys);
        properties.setProperty("waitSeconds", "1");
        properties.setProperty("retryDelayMs", "1");
        return properties;
    }

    private static final class FakeConsulTransport implements ConsulRemoteConfigProvider.ConsulHttpTransport {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Long> indexes = new HashMap<>();
        private final Map<String, Long> deleteOnBlocking = new HashMap<>();
        private final Map<String, Map<String, String>> prefixes = new HashMap<>();
        private final Map<String, Long> prefixIndexes = new HashMap<>();
        private final Map<String, AtomicInteger> blockingCalls = new HashMap<>();
        private volatile Map<String, String> lastHeaders = Map.of();
        private int prefixLoads;

        void putKey(String key, String value, long index) {
            values.put(key, value);
            indexes.put(key, index);
        }

        void putPrefix(String prefix, Map<String, String> values, long index) {
            prefixes.put(prefix, values);
            prefixIndexes.put(prefix, index);
        }

        void deleteOnFirstBlocking(String key, long index) {
            deleteOnBlocking.put(key, index);
        }

        @Override
        public ConsulRemoteConfigProvider.ConsulHttpResult get(
                URI uri,
                long timeoutMillis,
                Map<String, String> headers) throws Exception {
            lastHeaders = headers;
            String key = uri.getPath().substring("/v1/kv/".length());
            String query = uri.getQuery() == null ? "" : uri.getQuery();
            if (query.contains("recurse=true")) {
                prefixLoads++;
                return result(200, prefixBody(prefixes.getOrDefault(key, Map.of())), prefixIndexes.getOrDefault(key, 1L));
            }
            if (query.contains("index=") && blockingCalls.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet() == 1) {
                Long deleteIndex = deleteOnBlocking.get(key);
                if (deleteIndex != null) {
                    values.remove(key);
                    indexes.put(key, deleteIndex);
                } else if (key.equals("base")) {
                    putKey("base", "shared=base2\nbase.only=false\n", 11);
                }
            } else if (query.contains("index=")) {
                Thread.sleep(Math.min(timeoutMillis, 20));
            }
            if (!values.containsKey(key)) {
                return result(404, "", indexes.getOrDefault(key, 1L));
            }
            return result(200, values.get(key), indexes.get(key));
        }

        private ConsulRemoteConfigProvider.ConsulHttpResult result(int status, String body, long index) {
            return new ConsulRemoteConfigProvider.ConsulHttpResult(
                    status,
                    body,
                    Map.of("X-Consul-Index", List.of(String.valueOf(index))));
        }

        private String prefixBody(Map<String, String> prefixValues) {
            StringBuilder body = new StringBuilder("[");
            boolean first = true;
            for (Map.Entry<String, String> entry : prefixValues.entrySet()) {
                if (!first) {
                    body.append(',');
                }
                first = false;
                String encoded = Base64.getEncoder().encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8));
                body.append("{\"Key\":\"")
                        .append(entry.getKey())
                        .append("\",\"Value\":\"")
                        .append(encoded)
                        .append("\"}");
            }
            body.append(']');
            return body.toString();
        }
    }
}
