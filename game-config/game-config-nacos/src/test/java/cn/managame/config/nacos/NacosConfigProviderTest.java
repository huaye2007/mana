package cn.managame.config.nacos;

import cn.managame.config.ConfigException;
import cn.managame.config.ConfigLayer;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class NacosConfigProviderTest {
    private static ConfigLayer layer(String... properties) {
        ConfigLayer.Builder builder = ConfigLayer.builder("nacos")
                .endpoint("127.0.0.1:8848").resource("GAME:base").resource("GAME:override")
                .property("timeoutMillis", "1000");
        for (int index = 0; index < properties.length; index += 2) {
            builder.property(properties[index], properties[index + 1]);
        }
        return builder.build();
    }

    @Test void mergesResourcesAndPublishesCompleteSnapshots() throws Exception {
        ConfigService service = mock(ConfigService.class);
        when(service.getConfig("base", "GAME", 1000)).thenReturn("_revision=application-value\nport=7000\nname=base");
        when(service.getConfig("override", "GAME", 1000)).thenReturn("name=override");
        when(service.getServerStatus()).thenReturn("UP");
        var source = new NacosConfigProvider.NacosSource(layer(), service);

        Map<String, String> initial = Map.of(
                "_revision", "application-value", "port", "7000", "name", "override");
        assertEquals(initial, source.load());
        assertFalse(source.loadData().isVersioned());
        clearInvocations(service);

        AtomicReference<Map<String, String>> update = new AtomicReference<>();
        AtomicReference<Throwable> watchError = new AtomicReference<>();
        source.watch(update::set, watchError::set);
        ArgumentCaptor<Listener> listeners = ArgumentCaptor.forClass(Listener.class);
        verify(service, times(2)).addListener(anyString(), eq("GAME"), listeners.capture());

        listeners.getAllValues().getFirst().receiveConfigInfo("_revision=application-value\nport=8000\nname=base");
        // Only the changed resource is replaced; the other keeps overriding from cache.
        assertEquals(Map.of("_revision", "application-value", "port", "8000", "name", "override"), update.get());
        assertNull(watchError.get());
        // The callback carries the new document, so republishing costs no requests at all. Re-reading
        // the layer here used to turn one publish into one request per resource.
        verify(service, never()).getConfig(anyString(), anyString(), anyLong());

        source.close();
        verify(service, times(2)).removeListener(anyString(), eq("GAME"), any());
        verify(service).shutDown();
    }

    @Test void deletedConfigBecomesAnEmptyDocument() throws Exception {
        ConfigService service = mock(ConfigService.class);
        when(service.getConfig("base", "GAME", 1000)).thenReturn("port=7000\nname=base");
        when(service.getConfig("override", "GAME", 1000)).thenReturn("name=override");
        when(service.getServerStatus()).thenReturn("UP");
        var source = new NacosConfigProvider.NacosSource(layer(), service);
        source.load();

        AtomicReference<Map<String, String>> update = new AtomicReference<>();
        source.watch(update::set, error -> { throw new AssertionError(error); });
        ArgumentCaptor<Listener> listeners = ArgumentCaptor.forClass(Listener.class);
        verify(service, times(2)).addListener(anyString(), eq("GAME"), listeners.capture());

        listeners.getAllValues().getLast().receiveConfigInfo(null);
        assertEquals(Map.of("port", "7000", "name", "base"), update.get());
        source.close();
    }

    @Test void pingUsesLocalServerStatusRatherThanReadingDocuments() throws Exception {
        ConfigService service = mock(ConfigService.class);
        when(service.getServerStatus()).thenReturn("UP");
        var source = new NacosConfigProvider.NacosSource(layer(), service);

        assertDoesNotThrow(source::ping);
        verify(service, never()).getConfig(anyString(), anyString(), anyLong());

        when(service.getServerStatus()).thenReturn("DOWN");
        assertThrows(ConfigException.class, source::ping);
        source.close();
    }

    @Test void jsonDocumentsWorkWithoutRewritingThemAsProperties() throws Exception {
        ConfigService service = mock(ConfigService.class);
        when(service.getConfig("base", "GAME", 1000))
                .thenReturn("{\"game\":{\"server\":{\"port\":9000}},\"regions\":[\"cn\"]}");
        when(service.getConfig("override", "GAME", 1000)).thenReturn("{}");
        when(service.getServerStatus()).thenReturn("UP");
        // Format is a property of the document, not of the backend: a JSON dataId needs no rewrite.
        var source = new NacosConfigProvider.NacosSource(layer("format", "json"), service);

        Map<String, String> values = source.load();
        assertEquals("9000", values.get("game.server.port"));
        assertEquals("cn", values.get("regions[0]"));
        source.close();
    }

    @Test void formatFollowsTheDataIdWhenNotPinned() throws Exception {
        ConfigService service = mock(ConfigService.class);
        when(service.getConfig("app.json", "GAME", 1000)).thenReturn("{\"game\":{\"name\":\"mana\"}}");
        when(service.getConfig("app.properties", "GAME", 1000)).thenReturn("game.mode=prod");
        when(service.getServerStatus()).thenReturn("UP");
        var source = new NacosConfigProvider.NacosSource(ConfigLayer.builder("nacos")
                .endpoint("127.0.0.1:8848").resource("GAME:app.json").resource("GAME:app.properties")
                .property("timeoutMillis", "1000").build(), service);

        Map<String, String> values = source.load();
        assertEquals("mana", values.get("game.name"));
        assertEquals("prod", values.get("game.mode"));
        source.close();
    }

    @Test void unknownPinnedFormatFailsFast() {
        ConfigService service = mock(ConfigService.class);
        ConfigException error = assertThrows(ConfigException.class,
                () -> new NacosConfigProvider.NacosSource(layer("format", "toml"), service));
        assertTrue(error.getMessage().contains("toml"), error.getMessage());
    }
}
