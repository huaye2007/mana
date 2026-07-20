package cn.managame.config.nacos;

import cn.managame.config.ConfigException;
import cn.managame.config.ConfigOptions;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class NacosConfigProviderTest {
    @Test void mergesResourcesAndPublishesCompleteSnapshots() throws Exception {
        ConfigService service = mock(ConfigService.class);
        when(service.getConfig("base", "GAME", 1000))
                .thenReturn("_revision=application-value\nport=7000\nname=base",
                        "_revision=application-value\nport=7000\nname=base",
                        "_revision=application-value\nport=8000\nname=base");
        when(service.getConfig("override", "GAME", 1000))
                .thenReturn("name=override", "name=override", "name=latest");
        when(service.getServerStatus()).thenReturn("UP");
        var source = new NacosConfigProvider.NacosSource(ConfigOptions.builder("nacos")
                .endpoint("127.0.0.1:8848").resource("GAME:base").resource("GAME:override")
                .property("timeoutMillis", "1000").build(), service);

        Map<String, String> initial = Map.of(
                "_revision", "application-value", "port", "7000", "name", "override");
        assertEquals(initial, source.load());
        assertFalse(source.loadData().isVersioned());
        AtomicReference<Map<String, String>> update = new AtomicReference<>();
        AtomicReference<Throwable> watchError = new AtomicReference<>();
        source.watch(update::set, watchError::set);
        ArgumentCaptor<Listener> listeners = ArgumentCaptor.forClass(Listener.class);
        verify(service, times(2)).addListener(anyString(), eq("GAME"), listeners.capture());
        listeners.getAllValues().getFirst().receiveConfigInfo("ignored");
        assertEquals(Map.of("_revision", "application-value", "port", "8000", "name", "latest"), update.get());
        assertNull(watchError.get());
        when(service.getServerStatus()).thenReturn("DOWN");
        assertThrows(ConfigException.class, source::loadData);

        source.close();
        verify(service, times(2)).removeListener(anyString(), eq("GAME"), any());
        verify(service).shutDown();
    }
}
