package cn.managame.config.etcd;

import cn.managame.config.ConfigOptions;
import io.etcd.jetcd.Watch;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtcdConfigProviderTest {
    @Test void readsOneEtcdRevisionAndHandlesDelete() throws Exception {
        EtcdConfigProvider.ClientAdapter client = mock(EtcdConfigProvider.ClientAdapter.class);
        Watch.Watcher first = mock(Watch.Watcher.class);
        Watch.Watcher second = mock(Watch.Watcher.class);
        List<String> resources = List.of("/config/base", "/config/override");
        when(client.getAll(resources, 0, 1000)).thenReturn(new EtcdConfigProvider.VersionedContents(10,
                Map.of("/config/base", "port=7000\nname=base", "/config/override", "name=override")));
        when(client.getAll(resources, 11, 1000)).thenReturn(new EtcdConfigProvider.VersionedContents(11,
                Map.of("/config/base", "port=7000\nname=base", "/config/override", "")));
        when(client.watch(eq("/config/base"), eq(11L), any(), any())).thenReturn(first);
        when(client.watch(eq("/config/override"), eq(11L), any(), any())).thenReturn(second);
        var source = new EtcdConfigProvider.EtcdSource(ConfigOptions.builder("etcd")
                .endpoint("http://127.0.0.1:2379").resources(resources)
                .property("timeoutMillis", "1000").build(), client);

        assertEquals(Map.of("port", "7000", "name", "override"), source.load());
        AtomicReference<Map<String, String>> update = new AtomicReference<>();
        CountDownLatch changed = new CountDownLatch(1);
        source.watch(values -> { update.set(values); changed.countDown(); }, error -> { throw new AssertionError(error); });
        ArgumentCaptor<LongConsumer> callback = ArgumentCaptor.forClass(LongConsumer.class);
        verify(client).watch(eq("/config/override"), eq(11L), callback.capture(), any());
        callback.getValue().accept(11);
        assertTrue(changed.await(2, TimeUnit.SECONDS));
        assertEquals(Map.of("port", "7000", "name", "base"), update.get());

        source.close();
        verify(first).close();
        verify(second).close();
        verify(client).close();
    }
}
