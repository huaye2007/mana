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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class NacosConfigProviderTest {
    @Test void mergesResourcesAndPublishesCompleteSnapshots() throws Exception {
        ConfigService service = mock(ConfigService.class);
        when(service.getConfig("base", "GAME", 1000))
                .thenReturn("_revision=1\nport=7000\nname=base",
                        "_revision=2\nport=7000\nname=base", "_revision=2\nport=7000\nname=base");
        when(service.getConfig("override", "GAME", 1000))
                .thenReturn("_revision=1\nname=override", "_revision=1\nname=override", "_revision=2\nname=latest");
        when(service.getServerStatus()).thenReturn("UP");
        var source = new NacosConfigProvider.NacosSource(ConfigOptions.builder("nacos")
                .endpoint("127.0.0.1:8848").resource("GAME:base").resource("GAME:override")
                .property("timeoutMillis", "1000").build(), service);

        assertEquals(Map.of("port", "7000", "name", "override"), source.load());
        AtomicReference<Map<String, String>> update = new AtomicReference<>();
        AtomicReference<Throwable> watchError = new AtomicReference<>();
        source.watch(update::set, watchError::set);
        ArgumentCaptor<Listener> listeners = ArgumentCaptor.forClass(Listener.class);
        verify(service, times(2)).addListener(anyString(), eq("GAME"), listeners.capture());
        listeners.getAllValues().getFirst().receiveConfigInfo("ignored");
        assertNull(update.get());
        assertInstanceOf(ConfigException.class, watchError.get());
        listeners.getAllValues().get(1).receiveConfigInfo("ignored");
        assertEquals(Map.of("port", "7000", "name", "latest"), update.get());
        when(service.getServerStatus()).thenReturn("DOWN");
        assertThrows(ConfigException.class, source::loadData);

        source.close();
        verify(service, times(2)).removeListener(anyString(), eq("GAME"), any());
        verify(service).shutDown();
    }
}
