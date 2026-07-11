package cn.managame.config.etcd;

import cn.managame.config.ConfigOptions;
import io.etcd.jetcd.Watch;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtcdConfigProviderTest {
    @SuppressWarnings("unchecked")
    @Test void mergesResourcesAndHandlesPutAndDelete() throws Exception {
        EtcdConfigProvider.ClientAdapter client = mock(EtcdConfigProvider.ClientAdapter.class);
        Watch.Watcher first = mock(Watch.Watcher.class);
        Watch.Watcher second = mock(Watch.Watcher.class);
        when(client.get("/config/base", 1000)).thenReturn("port=7000\nname=base");
        when(client.get("/config/override", 1000)).thenReturn("name=override");
        when(client.watch(eq("/config/base"), any(), any())).thenReturn(first);
        when(client.watch(eq("/config/override"), any(), any())).thenReturn(second);
        var source = new EtcdConfigProvider.EtcdSource(ConfigOptions.builder("etcd")
                .endpoint("http://127.0.0.1:2379").resource("/config/base").resource("/config/override")
                .property("timeoutMillis", "1000").build(), client);

        assertEquals(Map.of("port", "7000", "name", "override"), source.load());
        AtomicReference<Map<String, String>> update = new AtomicReference<>();
        source.watch(update::set, error -> { throw new AssertionError(error); });
        ArgumentCaptor<Consumer<String>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(client).watch(eq("/config/override"), callback.capture(), any());
        callback.getValue().accept("");
        assertEquals(Map.of("port", "7000", "name", "base"), update.get());

        source.close();
        verify(first).close();
        verify(second).close();
        verify(client).close();
    }
}
