package cn.managame.config.nacos;

import cn.managame.config.ConfigOptions;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class NacosConfigProviderTest {
    @Test void mergesResourcesAndPublishesCompleteSnapshots() throws Exception {
        ConfigService service = mock(ConfigService.class);
        when(service.getConfig("base", "GAME", 1000)).thenReturn("port=7000\nname=base");
        when(service.getConfig("override", "GAME", 1000)).thenReturn("name=override");
        var source = new NacosConfigProvider.NacosSource(ConfigOptions.builder("nacos")
                .endpoint("127.0.0.1:8848").resource("GAME:base").resource("GAME:override")
                .property("timeoutMillis", "1000").build(), service);

        assertEquals(Map.of("port", "7000", "name", "override"), source.load());
        AtomicReference<Map<String, String>> update = new AtomicReference<>();
        source.watch(update::set, error -> { throw new AssertionError(error); });
        ArgumentCaptor<Listener> listeners = ArgumentCaptor.forClass(Listener.class);
        verify(service, times(2)).addListener(anyString(), eq("GAME"), listeners.capture());
        listeners.getAllValues().get(1).receiveConfigInfo("name=latest");
        assertEquals(Map.of("port", "7000", "name", "latest"), update.get());

        source.close();
        verify(service, times(2)).removeListener(anyString(), eq("GAME"), any());
        verify(service).shutDown();
    }
}
