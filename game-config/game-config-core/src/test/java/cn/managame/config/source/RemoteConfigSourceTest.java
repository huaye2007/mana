package cn.managame.config.source;

import cn.managame.config.spi.RemoteConfigProvider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteConfigSourceTest {
    @Test
    void shouldRejectNullProvider() {
        NullPointerException error = assertThrows(
                NullPointerException.class,
                () -> new RemoteConfigSource(null, new Properties()));

        assertEquals("provider must not be null", error.getMessage());
    }

    @Test
    void shouldCopyRemotePropertiesOnConstruction() {
        Properties properties = new Properties();
        properties.setProperty("profile", "prod");

        RemoteConfigSource source = new RemoteConfigSource(new RemoteConfigProvider() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                return Map.of("profile", remoteProperties.getProperty("profile"));
            }
        }, properties);

        properties.setProperty("profile", "test");

        assertEquals("prod", source.load().get("profile"));
    }

    @Test
    void shouldExposeProviderTypeInSourceName() {
        RemoteConfigSource source = new RemoteConfigSource(new RemoteConfigProvider() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                return Map.of();
            }
        }, new Properties());

        assertEquals("REMOTE:mock", source.name());
    }

    @Test
    void shouldReturnImmutableCopyOfPolledConfig() {
        Map<String, String> mutableConfig = new HashMap<>();
        mutableConfig.put("feature", "on");

        RemoteConfigSource source = new RemoteConfigSource(new RemoteConfigProvider() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                return mutableConfig;
            }
        }, new Properties());

        Map<String, String> snapshot = source.load();
        mutableConfig.put("feature", "off");

        assertEquals("on", snapshot.get("feature"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("new", "value"));
    }

    @Test
    void shouldServeCachedPushSnapshotWithoutPollingOnceSubscribed() {
        Map<String, String> pushedConfig = new HashMap<>();
        pushedConfig.put("switch", "on");
        AtomicReference<Consumer<Map<String, String>>> callbackRef = new AtomicReference<>();
        AtomicReference<Map<String, String>> remoteConfig = new AtomicReference<>(Map.of("switch", "initial"));
        AtomicInteger loadCalls = new AtomicInteger();

        RemoteConfigSource source = new RemoteConfigSource(new RemoteConfigProvider() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                loadCalls.incrementAndGet();
                return remoteConfig.get();
            }

            @Override
            public boolean supportsPush() {
                return true;
            }

            @Override
            public void subscribe(Properties remoteProperties, Consumer<Map<String, String>> callback) {
                callbackRef.set(callback);
            }
        }, new Properties());

        source.startSubscriptionIfSupported();
        callbackRef.get().accept(pushedConfig);
        pushedConfig.put("switch", "off");

        // subscribed source serves the cached push snapshot and never polls the network
        remoteConfig.set(Map.of("switch", "remote"));
        Map<String, String> snapshot = source.load();
        assertEquals("on", snapshot.get("switch"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("new", "value"));

        assertEquals("on", source.load().get("switch"));
        assertEquals(0, loadCalls.get());

        // a fresh push replaces the cache immediately
        callbackRef.get().accept(Map.of("switch", "pushed2"));
        assertEquals("pushed2", source.load().get("switch"));
        assertEquals(0, loadCalls.get());
    }

    @Test
    void shouldPollOnceToSeedCacheWhenSubscribedButNoPushYet() {
        AtomicReference<Map<String, String>> remoteConfig = new AtomicReference<>(Map.of("switch", "remote"));
        AtomicInteger loadCalls = new AtomicInteger();

        RemoteConfigSource source = new RemoteConfigSource(new RemoteConfigProvider() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                loadCalls.incrementAndGet();
                return remoteConfig.get();
            }

            @Override
            public boolean supportsPush() {
                return true;
            }

            @Override
            public void subscribe(Properties remoteProperties, Consumer<Map<String, String>> callback) {
                // no initial snapshot pushed
            }
        }, new Properties());

        source.startSubscriptionIfSupported();

        // no push yet: load() polls once to seed the cache
        assertEquals("remote", source.load().get("switch"));
        assertEquals(1, loadCalls.get());

        // subsequent loads are served from the seeded cache, no further polling
        remoteConfig.set(Map.of("switch", "changed"));
        assertEquals("remote", source.load().get("switch"));
        assertEquals(1, loadCalls.get());
    }

    @Test
    void shouldReportCallbackFailureThroughDefaultSubscribeOverload() {
        AtomicReference<Consumer<Map<String, String>>> callbackRef = new AtomicReference<>();
        AtomicReference<Exception> pushError = new AtomicReference<>();

        RemoteConfigSource source = new RemoteConfigSource(new RemoteConfigProvider() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                return Map.of("switch", "initial");
            }

            @Override
            public boolean supportsPush() {
                return true;
            }

            @Override
            public void subscribe(Properties remoteProperties, Consumer<Map<String, String>> callback) {
                callbackRef.set(callback);
            }
        }, new Properties());
        source.setOnPushUpdate(() -> {
            throw new IllegalStateException("manager reload failed");
        });
        source.setOnPushError(pushError::set);

        assertThrows(IllegalStateException.class, () -> {
            source.startSubscriptionIfSupported();
            callbackRef.get().accept(Map.of("switch", "pushed"));
        });

        assertEquals("manager reload failed", pushError.get().getMessage());
    }

    @Test
    void shouldSubscribeOnlyOnce() {
        AtomicInteger subscribeCalls = new AtomicInteger();

        RemoteConfigSource source = new RemoteConfigSource(new RemoteConfigProvider() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                return Map.of("switch", "initial");
            }

            @Override
            public boolean supportsPush() {
                return true;
            }

            @Override
            public void subscribe(Properties remoteProperties, Consumer<Map<String, String>> callback) {
                subscribeCalls.incrementAndGet();
            }
        }, new Properties());

        source.startSubscriptionIfSupported();
        source.startSubscriptionIfSupported();

        assertEquals(1, subscribeCalls.get());
    }
}
