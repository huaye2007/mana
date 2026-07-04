package cn.managame.config.etcd;

import cn.managame.config.exception.ConfigOperationException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtcdRemoteConfigProviderTest {
    @Test
    void shouldRejectInvalidTimeoutBeforeCreatingClient() {
        EtcdRemoteConfigProvider provider = new EtcdRemoteConfigProvider();
        Properties properties = new Properties();
        properties.setProperty("timeoutMs", "0");
        properties.setProperty("dataId", "/game/test");

        assertThrows(ConfigOperationException.class, () -> provider.load(properties));
    }

    @Test
    void shouldRejectMissingDataId() {
        EtcdRemoteConfigProvider provider = new EtcdRemoteConfigProvider(new FakeEtcdClientAdapter());

        ConfigOperationException error = assertThrows(
                ConfigOperationException.class,
                () -> provider.load(null));

        assertEquals("Etcd config requires dataId or dataIds", error.getMessage());
    }

    @Test
    void shouldLoadAndMergeMultipleKeysInDeclaredOrder() throws Exception {
        FakeEtcdClientAdapter fakeClient = new FakeEtcdClientAdapter();
        fakeClient.put("/base", "shared=base\nbase.only=true\n");
        fakeClient.put("/room", "shared=room\nroom.only=true\n");
        EtcdRemoteConfigProvider provider = new EtcdRemoteConfigProvider(fakeClient);

        Properties properties = new Properties();
        properties.setProperty("dataIds", "/base,/room");

        Map<String, String> config = provider.load(properties);

        assertEquals("room", config.get("shared"));
        assertEquals("true", config.get("base.only"));
        assertEquals("true", config.get("room.only"));
    }

    @Test
    void shouldPushFullMergedSnapshotWhenOneKeyChanges() throws Exception {
        FakeEtcdClientAdapter fakeClient = new FakeEtcdClientAdapter();
        fakeClient.put("/base", "shared=base\nbase.only=true\n");
        fakeClient.put("/room", "shared=room\nroom.only=true\n");
        EtcdRemoteConfigProvider provider = new EtcdRemoteConfigProvider(fakeClient);
        AtomicReference<Map<String, String>> pushed = new AtomicReference<>();

        Properties properties = new Properties();
        properties.setProperty("dataIds", "/base,/room");
        provider.subscribe(properties, pushed::set);

        fakeClient.push("/base", "shared=base2\nbase.only=false\n");

        assertEquals("room", pushed.get().get("shared"));
        assertEquals("false", pushed.get().get("base.only"));
        assertEquals("true", pushed.get().get("room.only"));
    }

    @Test
    void shouldCloseCreatedWatchersWhenSubscribeFails() {
        FakeEtcdClientAdapter fakeClient = new FakeEtcdClientAdapter();
        fakeClient.put("/base", "shared=base\nbase.only=true\n");
        fakeClient.put("/room", "shared=room\nroom.only=true\n");
        fakeClient.failWatchKey("/room");
        EtcdRemoteConfigProvider provider = new EtcdRemoteConfigProvider(fakeClient);

        Properties properties = new Properties();
        properties.setProperty("dataIds", "/base,/room");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> provider.subscribe(properties, ignored -> { }));

        assertEquals("watch failed", error.getMessage());
        assertEquals(1, fakeClient.closedWatchers);
        assertEquals(0, fakeClient.watchers.size());
    }

    @Test
    void shouldCloseWatchersAndClient() throws Exception {
        FakeEtcdClientAdapter fakeClient = new FakeEtcdClientAdapter();
        fakeClient.put("/base", "enabled=true\n");
        EtcdRemoteConfigProvider provider = new EtcdRemoteConfigProvider(fakeClient);
        Properties properties = new Properties();
        properties.setProperty("dataId", "/base");

        provider.subscribe(properties, ignored -> { });
        provider.close();

        assertEquals(1, fakeClient.closedWatchers);
        assertTrue(fakeClient.closed);
    }

    private static final class FakeEtcdClientAdapter implements EtcdRemoteConfigProvider.EtcdClientAdapter {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, java.util.function.Consumer<String>> watchers = new HashMap<>();
        private boolean closed;
        private int closedWatchers;
        private String failWatchKey;

        void put(String key, String value) {
            values.put(key, value);
        }

        void failWatchKey(String key) {
            failWatchKey = key;
        }

        void push(String key, String value) {
            values.put(key, value);
            watchers.get(key).accept(value);
        }

        @Override
        public String get(String key, long timeoutMs) {
            return values.getOrDefault(key, "");
        }

        @Override
        public EtcdRemoteConfigProvider.CloseableWatcher watch(
                String key,
                java.util.function.Consumer<String> callback) {
            if (key.equals(failWatchKey)) {
                throw new IllegalStateException("watch failed");
            }
            watchers.put(key, callback);
            return () -> {
                closedWatchers++;
                watchers.remove(key);
            };
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
