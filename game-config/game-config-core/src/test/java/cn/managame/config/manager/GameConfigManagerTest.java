package cn.managame.config.manager;

import cn.managame.config.factory.GameConfigOptions;
import cn.managame.config.loader.JsonLocalConfigLoader;
import cn.managame.config.source.*;
import cn.managame.config.spi.RemoteConfigProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameConfigManagerTest {
    @Test
    void shouldLoadClasspathPropertiesWhenConfigured() {
        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new ClasspathConfigSource("classpath-config.properties"));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();
        assertEquals("fromClasspath", manager.get("classpath.key"));
        assertTrue(manager.isHealthy());
        manager.close();
    }

    @Test
    void shouldUseJvmPropertiesWhenJvmPriorityIsHigher() throws Exception {
        Path tempFile = Files.createTempFile("game-config-", ".properties");
        Files.writeString(tempFile, "mixed.key=fromLocal\n");

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new JvmConfigSource(false, Map.of("mixed.key", "fromJvm")));
        options.addSource(new LocalFileConfigSource(tempFile.toString()));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();
        assertEquals("fromJvm", manager.get("mixed.key"));
        manager.close();
    }

    @Test
    void shouldUseLocalFileFirstByDefaultOrder() throws Exception {
        Path tempFile = Files.createTempFile("game-config-", ".properties");
        Files.writeString(tempFile, "priority.key=fromProperties\n");
        String envKey = System.getenv().keySet().stream().findFirst().orElse("PATH");

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new LocalFileConfigSource(tempFile.toString()));
        options.addSource(new CommandLineConfigSource(Map.of("priority.key", "fromCommandLine")));
        options.addSource(new EnvironmentConfigSource());
        options.addSource(new DefaultConfigSource(Map.of("priority.key", "fromDefault")));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();
        assertEquals("fromProperties", manager.get("priority.key"));
        assertEquals(System.getenv().get(envKey), manager.get(envKey));
        manager.close();
    }

    @Test
    void shouldUseCommandLineWhenPriorityAdjusted() throws Exception {
        Path tempFile = Files.createTempFile("game-config-", ".properties");
        Files.writeString(tempFile, "priority.key=fromProperties\n");

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        // 命令行优先级最高
        options.addSource(new CommandLineConfigSource(Map.of("priority.key", "fromCommandLine")));
        options.addSource(new LocalFileConfigSource(tempFile.toString()));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();
        assertEquals("fromCommandLine", manager.get("priority.key"));
        manager.close();
    }

    @Test
    void shouldFallbackToDefaultWhenOthersMissing() {
        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new DefaultConfigSource(Map.of("fallback.key", "defaultValue")));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();
        assertEquals("defaultValue", manager.get("fallback.key"));
        manager.close();
    }

    @Test
    void shouldExposeKeyLookupAndPrefixSnapshots() {
        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new DefaultConfigSource(Map.of(
                "server.host", "127.0.0.1",
                "server.port", "8080",
                "feature.enabled", "true")));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();

        assertTrue(manager.containsKey("server.host"));
        assertFalse(manager.containsKey("missing"));
        assertTrue(manager.keys().contains("server.port"));
        assertThrows(UnsupportedOperationException.class, () -> manager.keys().remove("server.port"));

        Map<String, String> serverConfig = manager.snapshotByPrefix("server.");
        assertEquals(2, serverConfig.size());
        assertEquals("127.0.0.1", serverConfig.get("server.host"));
        assertEquals("8080", serverConfig.get("server.port"));
        assertThrows(UnsupportedOperationException.class, () -> serverConfig.put("server.mode", "prod"));

        assertEquals(manager.snapshot(), manager.snapshotByPrefix(""));
        assertEquals(manager.snapshot(), manager.snapshotByPrefix(null));
        assertTrue(manager.snapshotByPrefix("missing.").isEmpty());
        manager.close();
    }

    @Test
    void shouldSkipFailedSourceWhenFailFastDisabled() {
        AtomicInteger errors = new AtomicInteger();
        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.setErrorHandler(e -> errors.incrementAndGet());
        options.addSource(failingSource());
        options.addSource(new DefaultConfigSource(Map.of("fallback.key", "defaultValue")));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();

        assertEquals("defaultValue", manager.get("fallback.key"));
        assertEquals(1, errors.get());
        manager.close();
    }

    @Test
    void shouldKeepLastGoodSourceSnapshotWhenRecoverableSourceFails() {
        AtomicBoolean failRemote = new AtomicBoolean(false);
        AtomicInteger errors = new AtomicInteger();

        ConfigSource remoteSource = new ConfigSource() {
            @Override
            public String name() {
                return "remote";
            }

            @Override
            public Map<String, String> load() {
                if (failRemote.get()) {
                    throw new IllegalStateException("remote unavailable");
                }
                return Map.of("shared.key", "remote", "remote.only", "present");
            }
        };

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.setErrorHandler(e -> errors.incrementAndGet());
        options.addSource(remoteSource);
        options.addSource(new DefaultConfigSource(Map.of(
                "shared.key", "default",
                "default.only", "present")));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();
        assertEquals("remote", manager.get("shared.key"));
        assertEquals("present", manager.get("remote.only"));

        failRemote.set(true);
        manager.reloadNow();

        assertEquals("remote", manager.get("shared.key"));
        assertEquals("present", manager.get("remote.only"));
        assertEquals("present", manager.get("default.only"));
        assertEquals(1, errors.get());
        manager.close();
    }

    @Test
    void shouldReflectLifecycleInIsHealthy() {
        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new DefaultConfigSource(Map.of("server.port", "8080")));

        GameConfigManager manager = new GameConfigManager(options);
        assertFalse(manager.isHealthy(), "not healthy before start");

        manager.start();
        assertTrue(manager.isHealthy());

        manager.close();
        assertFalse(manager.isHealthy(), "not healthy after close");
    }

    @Test
    void shouldNotHoldManagerLockWhileNotifyingListeners() throws Exception {
        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new DefaultConfigSource(Map.of("base", "true")));

        GameConfigManager manager = new GameConfigManager(options);
        CountDownLatch firstListenerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstListener = new CountDownLatch(1);
        CountDownLatch secondUpdateCompleted = new CountDownLatch(1);
        AtomicBoolean firstEvent = new AtomicBoolean(true);
        AtomicReference<Throwable> secondUpdateError = new AtomicReference<>();

        manager.start();
        manager.addChangeListener(event -> {
            if (firstEvent.compareAndSet(true, false)) {
                firstListenerEntered.countDown();
                try {
                    releaseFirstListener.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        Thread firstUpdate = new Thread(() -> manager.put("first", "1"), "config-test-first-update");
        firstUpdate.start();
        assertTrue(firstListenerEntered.await(1, TimeUnit.SECONDS));

        Thread secondUpdate = new Thread(() -> {
            try {
                manager.put("second", "2");
            } catch (Throwable t) {
                secondUpdateError.set(t);
            } finally {
                secondUpdateCompleted.countDown();
            }
        }, "config-test-second-update");
        secondUpdate.start();

        assertTrue(secondUpdateCompleted.await(1, TimeUnit.SECONDS));
        assertNull(secondUpdateError.get());
        assertEquals("2", manager.get("second"));

        releaseFirstListener.countDown();
        firstUpdate.join(1000L);
        secondUpdate.join(1000L);
        manager.close();
    }

    @Test
    void shouldIgnoreNullListenersAndBlankOverrideKeys() {
        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new DefaultConfigSource(Map.of("base", "true")));

        GameConfigManager manager = new GameConfigManager(options);
        manager.addChangeListener(null);
        manager.removeChangeListener(null);
        manager.start();

        manager.put(" ", "ignored");
        manager.update(" ", old -> "ignored");
        manager.removeOverride(" ");

        assertEquals("true", manager.get("base"));
        assertTrue(manager.getOverrides().isEmpty());
        manager.close();
    }

    @Test
    void shouldThrowWhenFailFastEnabled() {
        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.setFailFast(true);
        options.addSource(failingSource());
        options.addSource(new DefaultConfigSource(Map.of("fallback.key", "defaultValue")));

        GameConfigManager manager = new GameConfigManager(options);

        assertThrows(IllegalStateException.class, manager::start);
        manager.close();
    }

    @Test
    void shouldCloseRemoteProviderWhenStartFailsAfterSubscription() {
        AtomicInteger subscribeCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();

        RemoteConfigProvider provider = new RemoteConfigProvider() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                return Map.of("port", "70000");
            }

            @Override
            public boolean supportsPush() {
                return true;
            }

            @Override
            public void subscribe(Properties remoteProperties, java.util.function.Consumer<Map<String, String>> callback) {
                subscribeCalls.incrementAndGet();
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        };

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new RemoteConfigSource(provider, new Properties()));
        options.addValidator(config -> {
            int port = Integer.parseInt(config.get("port"));
            if (port > 65535) {
                throw new IllegalArgumentException("port out of range");
            }
        });

        GameConfigManager manager = new GameConfigManager(options);

        assertThrows(IllegalArgumentException.class, manager::start);
        assertEquals(1, subscribeCalls.get());
        assertEquals(1, closeCalls.get());
        assertFalse(manager.isHealthy());
        assertThrows(IllegalStateException.class, manager::start);
    }

    @Test
    void shouldFallBackToPollingWhenRemoteSubscriptionFailsRecoverably() {
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();

        RemoteConfigProvider provider = new RemoteConfigProvider() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                return Map.of("mode", "fallback-polling");
            }

            @Override
            public boolean supportsPush() {
                return true;
            }

            @Override
            public void subscribe(Properties remoteProperties, java.util.function.Consumer<Map<String, String>> callback) {
                throw new IllegalStateException("subscribe unavailable");
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        };

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.setErrorHandler(e -> errors.incrementAndGet());
        options.addSource(new RemoteConfigSource(provider, new Properties()));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();

        assertEquals("fallback-polling", manager.get("mode"));
        assertEquals(1, errors.get());
        assertEquals(0, closeCalls.get());
        assertTrue(manager.isHealthy(), "polling fallback keeps the manager serving");

        manager.close();
        assertEquals(1, closeCalls.get());
    }

    @Test
    void shouldRecoverWhenRemoteSubscriptionRetrySucceeds() {
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger subscribeCalls = new AtomicInteger();

        RemoteConfigProvider provider = new RemoteConfigProvider() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                return Map.of("mode", "fallback-polling");
            }

            @Override
            public boolean supportsPush() {
                return true;
            }

            @Override
            public void subscribe(Properties remoteProperties, java.util.function.Consumer<Map<String, String>> callback) {
                if (subscribeCalls.incrementAndGet() == 1) {
                    throw new IllegalStateException("subscribe unavailable");
                }
            }
        };

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.setErrorHandler(e -> errors.incrementAndGet());
        options.addSource(new RemoteConfigSource(provider, new Properties()));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();
        assertEquals(1, errors.get());

        // each reload retries the subscription for not-yet-subscribed push sources
        manager.reloadNow();

        assertEquals(2, subscribeCalls.get());
        assertEquals(1, errors.get());
        assertTrue(manager.isHealthy());
        manager.close();
    }

    @Test
    void shouldBecomeUnhealthyWhenRemotePushReportsError() {
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<java.util.function.Consumer<Exception>> errorCallbackRef = new AtomicReference<>();

        RemoteConfigProvider provider = new RemoteConfigProvider() {
            @Override
            public String type() {
                return "mock";
            }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                return Map.of("mode", "stable");
            }

            @Override
            public boolean supportsPush() {
                return true;
            }

            @Override
            public void subscribe(Properties remoteProperties,
                                  java.util.function.Consumer<Map<String, String>> callback,
                                  java.util.function.Consumer<Exception> errorCallback) {
                errorCallbackRef.set(errorCallback);
            }
        };

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.setErrorHandler(e -> errors.incrementAndGet());
        options.addSource(new RemoteConfigSource(provider, new Properties()));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();
        assertTrue(manager.isHealthy());

        errorCallbackRef.get().accept(new IllegalStateException("push parse failed"));

        assertFalse(manager.isHealthy());
        assertEquals("stable", manager.get("mode"));
        assertEquals(1, errors.get());
        manager.close();
    }

    @Test
    void shouldRejectMalformedJsonConfig() throws Exception {
        Path tempFile = Files.createTempFile("game-config-", ".json");
        Files.writeString(tempFile, "{\"server\":{\"port\":8080}");

        JsonLocalConfigLoader loader = new JsonLocalConfigLoader(tempFile.toString());

        assertThrows(RuntimeException.class, loader::load);
    }

    @Test
    void shouldUseRemoteWhenRemoteFirst() throws Exception {
        Path tempFile = Files.createTempFile("game-config-", ".properties");
        Files.writeString(tempFile, "k1=local\nk2=localOnly\n");

        RemoteConfigProvider provider = mockProvider(Map.of("k1", "remote", "k3", "remoteOnly"));

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        // 远程优先级最高
        options.addSource(new RemoteConfigSource(provider, new Properties()));
        options.addSource(new LocalFileConfigSource(tempFile.toString()));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();
        assertEquals("remote", manager.get("k1"));
        assertEquals("localOnly", manager.get("k2"));
        assertEquals("remoteOnly", manager.get("k3"));
        manager.close();
    }

    @Test
    void shouldHotReloadWithPolling() throws Exception {
        Path tempFile = Files.createTempFile("game-config-", ".properties");
        Files.writeString(tempFile, "switch=off\n");

        AtomicReference<Map<String, String>> remoteRef = new AtomicReference<>(new HashMap<>());
        RemoteConfigProvider provider = new RemoteConfigProvider() {
            @Override
            public String type() { return "mock"; }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                return new HashMap<>(remoteRef.get());
            }
        };

        GameConfigOptions options = new GameConfigOptions();
        options.setRefreshIntervalMillis(100);
        options.setHotReloadEnabled(true);
        options.addSource(new RemoteConfigSource(provider, new Properties()));
        options.addSource(new LocalFileConfigSource(tempFile.toString()));

        GameConfigManager manager = new GameConfigManager(options);

        AtomicReference<Boolean> updated = new AtomicReference<>(false);
        manager.addChangeListener(event -> {
            if (event.getChangedKeys().contains("switch")) {
                updated.set(true);
            }
        });

        manager.start();
        assertEquals("off", manager.get("switch"));

        remoteRef.set(Map.of("switch", "on"));
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline && !"on".equals(manager.get("switch"))) {
            Thread.sleep(50L);
        }

        assertEquals("on", manager.get("switch"));
        assertTrue(updated.get());
        manager.close();
    }

    @Test
    void shouldKeepLastValidSourceSnapshotWhenValidationFails() {
        AtomicReference<Map<String, String>> sourceRef =
                new AtomicReference<>(Map.of("port", "8080", "mode", "stable"));
        AtomicBoolean failSource = new AtomicBoolean(false);

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new ConfigSource() {
            @Override
            public String name() {
                return "mutable";
            }

            @Override
            public Map<String, String> load() {
                if (failSource.get()) {
                    throw new IllegalStateException("source unavailable");
                }
                return sourceRef.get();
            }
        });
        options.addValidator(config -> {
            int port = Integer.parseInt(config.get("port"));
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range");
            }
        });

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();
        assertEquals("stable", manager.get("mode"));

        sourceRef.set(Map.of("port", "70000", "mode", "invalid"));
        assertThrows(IllegalArgumentException.class, manager::reloadNow);
        assertEquals("stable", manager.get("mode"));

        // the invalid load must not poison the last-known-good fallback
        failSource.set(true);
        manager.reloadNow();
        assertEquals("stable", manager.get("mode"));

        manager.put("feature", "enabled");
        assertEquals("stable", manager.get("mode"));
        assertEquals("enabled", manager.get("feature"));
        manager.close();
    }

    @Test
    void shouldRollbackOverrideWhenValidationFails() {
        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new DefaultConfigSource(Map.of("port", "8080", "mode", "stable")));
        options.addValidator(config -> {
            int port = Integer.parseInt(config.get("port"));
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range");
            }
        });

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();

        assertThrows(IllegalArgumentException.class, () -> manager.put("port", "70000"));
        assertEquals("8080", manager.get("port"));
        assertTrue(manager.getOverrides().isEmpty());

        manager.put("mode", "canary");
        assertEquals("canary", manager.get("mode"));
        manager.close();
    }

    @Test
    void updateShouldNotOverwriteConcurrentOverrideWithStaleValue() throws Exception {
        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new DefaultConfigSource(Map.of("counter", "1")));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();

        CountDownLatch updaterEntered = new CountDownLatch(1);
        CountDownLatch releaseUpdater = new CountDownLatch(1);
        CountDownLatch putStarted = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread updateThread = new Thread(() -> {
            try {
                manager.update("counter", oldValue -> {
                    updaterEntered.countDown();
                    try {
                        releaseUpdater.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return String.valueOf(Integer.parseInt(oldValue) + 1);
                });
            } catch (Throwable t) {
                error.set(t);
            }
        }, "config-test-update");

        updateThread.start();
        assertTrue(updaterEntered.await(1, TimeUnit.SECONDS));

        Thread putThread = new Thread(() -> {
            putStarted.countDown();
            manager.put("counter", "100");
        }, "config-test-put");
        putThread.start();
        assertTrue(putStarted.await(1, TimeUnit.SECONDS));
        Thread.sleep(50L);

        releaseUpdater.countDown();
        updateThread.join(1000L);
        putThread.join(1000L);

        assertNull(error.get());
        assertEquals("100", manager.get("counter"));
        manager.close();
    }

    @Test
    void shouldSerializeConcurrentReloadsSoNewestReloadWins() throws Exception {
        AtomicInteger loadCalls = new AtomicInteger();
        CountDownLatch slowReloadStarted = new CountDownLatch(1);
        CountDownLatch releaseSlowReload = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new ConfigSource() {
            @Override
            public String name() {
                return "controlled";
            }

            @Override
            public Map<String, String> load() {
                int call = loadCalls.incrementAndGet();
                if (call == 1) {
                    return Map.of("mode", "initial");
                }
                if (call == 2) {
                    slowReloadStarted.countDown();
                    try {
                        if (!releaseSlowReload.await(2, TimeUnit.SECONDS)) {
                            throw new AssertionError("timed out waiting to release slow reload");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return Map.of("mode", "slow");
                }
                return Map.of("mode", "fast");
            }
        });

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();

        Thread firstReload = new Thread(() -> {
            try {
                manager.reloadNow();
            } catch (Throwable t) {
                firstError.set(t);
            }
        }, "config-test-first-reload");
        firstReload.start();
        assertTrue(slowReloadStarted.await(1, TimeUnit.SECONDS));

        Thread secondReload = new Thread(() -> {
            try {
                manager.reloadNow();
            } catch (Throwable t) {
                secondError.set(t);
            }
        }, "config-test-second-reload");
        secondReload.start();

        long deadline = System.currentTimeMillis() + 500L;
        while (loadCalls.get() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        releaseSlowReload.countDown();
        firstReload.join(2000L);
        secondReload.join(2000L);

        assertNull(firstError.get());
        assertNull(secondError.get());
        assertEquals("fast", manager.get("mode"));
        manager.close();
    }

    @Test
    void shouldNotPublishReloadResultAfterCloseStarts() throws Exception {
        AtomicInteger loadCalls = new AtomicInteger();
        CountDownLatch reloadStarted = new CountDownLatch(1);
        CountDownLatch releaseReload = new CountDownLatch(1);
        AtomicReference<Throwable> reloadError = new AtomicReference<>();

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new ConfigSource() {
            @Override
            public String name() {
                return "controlled";
            }

            @Override
            public Map<String, String> load() {
                int call = loadCalls.incrementAndGet();
                if (call == 1) {
                    return Map.of("mode", "initial");
                }
                reloadStarted.countDown();
                try {
                    if (!releaseReload.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release reload");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return Map.of("mode", "late");
            }
        });

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();

        Thread reloadThread = new Thread(() -> {
            try {
                manager.reloadNow();
            } catch (Throwable t) {
                reloadError.set(t);
            }
        }, "config-test-reload-before-close");
        reloadThread.start();
        assertTrue(reloadStarted.await(1, TimeUnit.SECONDS));

        Thread closeThread = new Thread(manager::close, "config-test-close");
        closeThread.start();
        // close() publishes the closed flag before contending for the lock
        long closeDeadline = System.currentTimeMillis() + 1000L;
        while (manager.isHealthy() && System.currentTimeMillis() < closeDeadline) {
            Thread.sleep(10L);
        }
        assertFalse(manager.isHealthy());

        releaseReload.countDown();
        reloadThread.join(2000L);
        closeThread.join(2000L);

        assertNull(reloadError.get());
        assertEquals("initial", manager.get("mode"));
    }

    @Test
    void shouldAllowCloseWhileStartIsLoading() throws Exception {
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        AtomicReference<Throwable> startError = new AtomicReference<>();

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(true);
        options.addSource(new ConfigSource() {
            @Override
            public String name() {
                return "controlled";
            }

            @Override
            public Map<String, String> load() {
                loadStarted.countDown();
                try {
                    if (!releaseLoad.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release startup load");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return Map.of("mode", "late-start");
            }
        });

        GameConfigManager manager = new GameConfigManager(options);
        Thread startThread = new Thread(() -> {
            try {
                manager.start();
            } catch (Throwable t) {
                startError.set(t);
            }
        }, "config-test-start");
        startThread.start();

        assertTrue(loadStarted.await(1, TimeUnit.SECONDS));
        Thread closeThread = new Thread(manager::close, "config-test-close-during-start");
        closeThread.start();
        // close() sets the closed flag before blocking on the lock held by start(); wait until
        // closeThread is blocked so the in-flight startup load is guaranteed to observe closed.
        long deadline = System.currentTimeMillis() + 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.State state = closeThread.getState();
            if (state == Thread.State.BLOCKED || state == Thread.State.WAITING
                    || state == Thread.State.TIMED_WAITING) {
                break;
            }
            Thread.sleep(5L);
        }

        releaseLoad.countDown();
        startThread.join(2000L);
        closeThread.join(2000L);

        assertNull(startError.get());
        assertFalse(manager.isHealthy());
        assertNull(manager.get("mode"));
    }

    @Test
    void shouldNotDeadlockWhenPushArrivesDuringStart() throws Exception {
        for (int iteration = 0; iteration < 30; iteration++) {
            AtomicReference<java.util.function.Consumer<Map<String, String>>> callbackRef = new AtomicReference<>();
            CountDownLatch subscribed = new CountDownLatch(1);
            RemoteConfigProvider provider = new RemoteConfigProvider() {
                @Override
                public String type() {
                    return "mock";
                }

                @Override
                public Map<String, String> load(Properties remoteProperties) {
                    return Map.of("mode", "loaded");
                }

                @Override
                public boolean supportsPush() {
                    return true;
                }

                @Override
                public void subscribe(Properties remoteProperties,
                                      java.util.function.Consumer<Map<String, String>> callback) {
                    callbackRef.set(callback);
                    subscribed.countDown();
                }
            };

            GameConfigOptions options = new GameConfigOptions();
            options.setHotReloadEnabled(false);
            options.addSource(new RemoteConfigSource(provider, new Properties()));
            GameConfigManager manager = new GameConfigManager(options);

            Thread pusher = new Thread(() -> {
                try {
                    subscribed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // Race a push-triggered reloadNow() against start()'s own reloadNow().
                for (int i = 0; i < 4; i++) {
                    java.util.function.Consumer<Map<String, String>> callback = callbackRef.get();
                    if (callback != null) {
                        callback.accept(Map.of("mode", "pushed-" + i));
                    }
                }
            }, "config-test-push-during-start");

            pusher.start();
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), manager::start);
            pusher.join(2000L);
            manager.close();
        }
    }

    @Test
    void shouldContinueHotReloadWhenErrorHandlerThrows() throws Exception {
        AtomicReference<Map<String, String>> sourceRef =
                new AtomicReference<>(Map.of("port", "8080", "mode", "stable"));
        AtomicInteger errors = new AtomicInteger();

        GameConfigOptions options = new GameConfigOptions();
        options.setRefreshIntervalMillis(100);
        options.setHotReloadEnabled(true);
        options.setErrorHandler(e -> {
            errors.incrementAndGet();
            throw new IllegalStateException("handler failed");
        });
        options.addSource(new ConfigSource() {
            @Override
            public String name() {
                return "mutable";
            }

            @Override
            public Map<String, String> load() {
                return sourceRef.get();
            }
        });
        options.addValidator(config -> {
            int port = Integer.parseInt(config.get("port"));
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range");
            }
        });

        GameConfigManager manager = new GameConfigManager(options);
        try {
            manager.start();
            sourceRef.set(Map.of("port", "70000", "mode", "invalid"));
            long errorDeadline = System.currentTimeMillis() + 2000L;
            while (System.currentTimeMillis() < errorDeadline && errors.get() == 0) {
                Thread.sleep(50L);
            }
            assertTrue(errors.get() > 0);

            sourceRef.set(Map.of("port", "9090", "mode", "recovered"));
            long recoveryDeadline = System.currentTimeMillis() + 2500L;
            while (System.currentTimeMillis() < recoveryDeadline && !"recovered".equals(manager.get("mode"))) {
                Thread.sleep(50L);
            }
            assertEquals("recovered", manager.get("mode"));
        } finally {
            manager.close();
        }
    }

    @Test
    void shouldDispatchListenerOnConfiguredExecutor() throws Exception {
        java.util.concurrent.ExecutorService listenerPool = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "config-listener-test");
            t.setDaemon(true);
            return t;
        });
        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.setListenerExecutor(listenerPool);
        options.addSource(new DefaultConfigSource(Map.of("base", "true")));

        GameConfigManager manager = new GameConfigManager(options);
        AtomicReference<String> listenerThread = new AtomicReference<>();
        CountDownLatch notified = new CountDownLatch(1);
        manager.addChangeListener(event -> {
            listenerThread.set(Thread.currentThread().getName());
            notified.countDown();
        });
        manager.start();

        manager.put("feature", "on");
        assertTrue(notified.await(2, TimeUnit.SECONDS));
        assertEquals("config-listener-test", listenerThread.get());

        manager.close();
        listenerPool.shutdownNow();
    }

    @Test
    void shouldNotBreakWhenListenerThrows() {
        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new DefaultConfigSource(Map.of("base", "true")));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();
        manager.addChangeListener(event -> {
            throw new IllegalStateException("listener boom");
        });

        manager.put("k", "v");
        manager.put("k", "v2");

        assertEquals("v2", manager.get("k"));
        manager.close();
    }

    @Test
    void shouldApplyPushedConfigUpdates() {
        AtomicReference<java.util.function.Consumer<Map<String, String>>> callbackRef = new AtomicReference<>();
        RemoteConfigProvider provider = new RemoteConfigProvider() {
            @Override
            public String type() { return "mock"; }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                return Map.of("mode", "initial");
            }

            @Override
            public boolean supportsPush() { return true; }

            @Override
            public void subscribe(Properties remoteProperties,
                                  java.util.function.Consumer<Map<String, String>> callback) {
                callbackRef.set(callback);
            }
        };

        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new RemoteConfigSource(provider, new Properties()));

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();

        callbackRef.get().accept(Map.of("mode", "pushed"));
        callbackRef.get().accept(Map.of("mode", "pushed2"));

        assertEquals("pushed2", manager.get("mode"));
        manager.close();
    }

    @Test
    void shouldParseTypedValues() {
        AtomicReference<Map<String, String>> sourceRef = new AtomicReference<>(Map.of(
                "int.key", "42",
                "long.key", "9999999999",
                "double.key", "3.5",
                "bool.key", "true",
                "bad.int", "abc"));
        GameConfigOptions options = new GameConfigOptions();
        options.setHotReloadEnabled(false);
        options.addSource(new ConfigSource() {
            @Override
            public String name() {
                return "mutable";
            }

            @Override
            public Map<String, String> load() {
                return sourceRef.get();
            }
        });

        GameConfigManager manager = new GameConfigManager(options);
        manager.start();

        assertEquals(42, manager.getInt("int.key", -1));
        assertEquals(9999999999L, manager.getLong("long.key", -1L));
        assertEquals(3.5, manager.getDouble("double.key", -1.0));
        assertTrue(manager.getBoolean("bool.key", false));
        // unparseable / missing fall back to default
        assertEquals(7, manager.getInt("bad.int", 7));
        assertEquals(5, manager.getInt("missing.key", 5));

        // a real config change is reflected by typed getters
        sourceRef.set(Map.of("int.key", "100"));
        manager.reloadNow();
        assertEquals(100, manager.getInt("int.key", -1));

        // runtime overrides are visible to typed getters too
        manager.put("int.key", "256");
        assertEquals(256, manager.getInt("int.key", -1));
        manager.close();
    }

    private RemoteConfigProvider mockProvider(Map<String, String> values) {
        return new RemoteConfigProvider() {
            @Override
            public String type() { return "mock"; }

            @Override
            public Map<String, String> load(Properties remoteProperties) { return values; }
        };
    }

    private ConfigSource failingSource() {
        return new ConfigSource() {
            @Override
            public String name() {
                return "FAILING";
            }

            @Override
            public Map<String, String> load() {
                throw new IllegalStateException("boom");
            }
        };
    }
}
