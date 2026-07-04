package cn.managame.config.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import cn.managame.config.exception.ConfigOperationException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NacosRemoteConfigProviderTest {
    @Test
    void shouldRejectInvalidTimeoutBeforeCreatingClient() {
        NacosRemoteConfigProvider provider = new NacosRemoteConfigProvider();
        Properties properties = new Properties();
        properties.setProperty("timeoutMs", "0");
        properties.setProperty("dataId", "game-test");

        assertThrows(ConfigOperationException.class, () -> provider.load(properties));
    }

    @Test
    void shouldRejectMissingDataId() {
        NacosRemoteConfigProvider provider = new NacosRemoteConfigProvider(new FakeConfigService().proxy());

        ConfigOperationException error = assertThrows(
                ConfigOperationException.class,
                () -> provider.load(null));

        assertEquals("Nacos config requires dataId or dataIds", error.getMessage());
    }

    @Test
    void shouldLoadAndMergeMultipleDataIdsInDeclaredOrder() throws Exception {
        FakeConfigService fakeService = new FakeConfigService();
        fakeService.put("G1", "base", "shared=base\nbase.only=true\n");
        fakeService.put("G2", "room", "shared=room\nroom.only=true\n");
        NacosRemoteConfigProvider provider = new NacosRemoteConfigProvider(fakeService.proxy());

        Properties properties = new Properties();
        properties.setProperty("dataIds", "G1:base,G2:room");

        Map<String, String> config = provider.load(properties);

        assertEquals("room", config.get("shared"));
        assertEquals("true", config.get("base.only"));
        assertEquals("true", config.get("room.only"));
    }

    @Test
    void shouldPushFullMergedSnapshotWhenOneDataIdChanges() throws Exception {
        FakeConfigService fakeService = new FakeConfigService();
        fakeService.put("G1", "base", "shared=base\nbase.only=true\n");
        fakeService.put("G2", "room", "shared=room\nroom.only=true\n");
        NacosRemoteConfigProvider provider = new NacosRemoteConfigProvider(fakeService.proxy());
        AtomicReference<Map<String, String>> pushed = new AtomicReference<>();

        Properties properties = new Properties();
        properties.setProperty("dataIds", "G1:base,G2:room");
        provider.subscribe(properties, pushed::set);

        fakeService.listener("G1", "base").receiveConfigInfo("shared=base2\nbase.only=false\n");

        assertEquals("room", pushed.get().get("shared"));
        assertEquals("false", pushed.get().get("base.only"));
        assertEquals("true", pushed.get().get("room.only"));
    }

    @Test
    void shouldRemoveCreatedListenersWhenSubscribeFails() {
        FakeConfigService fakeService = new FakeConfigService();
        fakeService.put("G1", "base", "shared=base\nbase.only=true\n");
        fakeService.put("G2", "room", "shared=room\nroom.only=true\n");
        fakeService.failOnAddCall(2);
        NacosRemoteConfigProvider provider = new NacosRemoteConfigProvider(fakeService.proxy());

        Properties properties = new Properties();
        properties.setProperty("dataIds", "G1:base,G2:room");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> provider.subscribe(properties, ignored -> { }));

        assertEquals("add listener failed", error.getMessage());
        assertTrue(fakeService.listeners.isEmpty());
        assertEquals(1, fakeService.removeListenerCalls);
    }

    @Test
    void shouldCloseInjectedConfigService() {
        FakeConfigService fakeService = new FakeConfigService();
        NacosRemoteConfigProvider provider = new NacosRemoteConfigProvider(fakeService.proxy());

        provider.close();

        assertTrue(fakeService.closed);
    }

    private static final class FakeConfigService {
        private final Map<String, String> contents = new HashMap<>();
        private final Map<String, Listener> listeners = new HashMap<>();
        private boolean closed;
        private int addListenerCalls;
        private int removeListenerCalls;
        private int failOnAddCall = -1;

        void put(String group, String dataId, String content) {
            contents.put(key(group, dataId), content);
        }

        void failOnAddCall(int call) {
            failOnAddCall = call;
        }

        Listener listener(String group, String dataId) {
            return listeners.get(key(group, dataId));
        }

        ConfigService proxy() {
            return (ConfigService) Proxy.newProxyInstance(
                    ConfigService.class.getClassLoader(),
                    new Class<?>[]{ConfigService.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getConfig" -> contents.getOrDefault(key((String) args[1], (String) args[0]), "");
                        case "addListener" -> {
                            addListenerCalls++;
                            if (addListenerCalls == failOnAddCall) {
                                throw new IllegalStateException("add listener failed");
                            }
                            listeners.put(key((String) args[1], (String) args[0]), (Listener) args[2]);
                            yield null;
                        }
                        case "removeListener" -> {
                            removeListenerCalls++;
                            listeners.remove(key((String) args[1], (String) args[0]));
                            yield null;
                        }
                        case "shutDown" -> {
                            closed = true;
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private static String key(String group, String dataId) {
            return group + "@@" + dataId;
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) {
                return null;
            }
            if (boolean.class.equals(returnType)) {
                return false;
            }
            if (void.class.equals(returnType)) {
                return null;
            }
            return 0;
        }
    }
}
