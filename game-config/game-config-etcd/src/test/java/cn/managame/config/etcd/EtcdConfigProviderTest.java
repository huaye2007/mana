package cn.managame.config.etcd;

import cn.managame.config.ConfigException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtcdConfigProviderTest {
    private static final List<String> RESOURCES = List.of("/config/base", "/config/override");

    private static ConfigOptions options(String... properties) {
        ConfigOptions.Builder builder = ConfigOptions.builder("etcd")
                .endpoint("http://127.0.0.1:2379").resources(RESOURCES)
                .property("timeoutMillis", "1000");
        for (int index = 0; index < properties.length; index += 2) {
            builder.property(properties[index], properties[index + 1]);
        }
        return builder.build();
    }

    @Test void readsOneEtcdRevisionAndHandlesDelete() throws Exception {
        EtcdConfigProvider.ClientAdapter client = mock(EtcdConfigProvider.ClientAdapter.class);
        Watch.Watcher first = mock(Watch.Watcher.class);
        Watch.Watcher second = mock(Watch.Watcher.class);
        when(client.getAll(RESOURCES, 0, 1000)).thenReturn(new EtcdConfigProvider.VersionedContents(10,
                Map.of("/config/base", "port=7000\nname=base", "/config/override", "name=override")));
        when(client.getAll(RESOURCES, 11, 1000)).thenReturn(new EtcdConfigProvider.VersionedContents(11,
                Map.of("/config/base", "port=7000\nname=base", "/config/override", "")));
        when(client.watch(eq("/config/base"), eq(11L), any(), any())).thenReturn(first);
        when(client.watch(eq("/config/override"), eq(11L), any(), any())).thenReturn(second);
        var source = new EtcdConfigProvider.EtcdSource(options(), client);

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

    @Test void watchRegisteredBeforeAnyLoadStartsFromNow() {
        EtcdConfigProvider.ClientAdapter client = mock(EtcdConfigProvider.ClientAdapter.class);
        when(client.watch(anyString(), anyLong(), any(), any())).thenReturn(mock(Watch.Watcher.class));
        var source = new EtcdConfigProvider.EtcdSource(options(), client);

        source.watch(values -> { }, error -> { });
        // Revision 0 means "from now". Asking for revision 1 on a long-lived cluster would either
        // replay history or fail outright against a compacted store.
        verify(client).watch(eq("/config/base"), eq(0L), any(), any());
        source.close();
    }

    @Test void pingReadsAHeaderRatherThanTheDocuments() throws Exception {
        EtcdConfigProvider.ClientAdapter client = mock(EtcdConfigProvider.ClientAdapter.class);
        when(client.revision(1000)).thenReturn(42L);
        var source = new EtcdConfigProvider.EtcdSource(options(), client);

        source.ping();
        verify(client).revision(1000);
        verify(client, never()).getAll(anyList(), anyLong(), anyLong());
        source.close();
    }

    @Test void jsonDocumentsWorkWithoutRewritingThemAsProperties() throws Exception {
        EtcdConfigProvider.ClientAdapter client = mock(EtcdConfigProvider.ClientAdapter.class);
        when(client.getAll(RESOURCES, 0, 1000)).thenReturn(new EtcdConfigProvider.VersionedContents(7,
                Map.of("/config/base", "{\"game\":{\"server\":{\"port\":9000}},\"regions\":[\"cn\"]}",
                        "/config/override", "{}")));
        // Format is a property of the document, not of the backend: the same JSON that reads from a
        // file reads from an Etcd value.
        var source = new EtcdConfigProvider.EtcdSource(options("format", "json"), client);

        Map<String, String> values = source.load();
        assertEquals("9000", values.get("game.server.port"));
        assertEquals("cn", values.get("regions[0]"));
        source.close();
    }

    @Test void unknownPinnedFormatFailsFast() {
        EtcdConfigProvider.ClientAdapter client = mock(EtcdConfigProvider.ClientAdapter.class);
        ConfigException error = assertThrows(ConfigException.class,
                () -> new EtcdConfigProvider.EtcdSource(options("format", "toml"), client));
        assertTrue(error.getMessage().contains("toml"), error.getMessage());
    }
}
