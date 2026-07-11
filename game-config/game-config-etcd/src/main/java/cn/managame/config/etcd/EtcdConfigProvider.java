package cn.managame.config.etcd;

import cn.managame.config.ConfigOptions;
import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;
import cn.managame.config.support.PropertiesDocument;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.watch.WatchEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class EtcdConfigProvider implements ConfigProvider {
    @Override public String type() { return "etcd"; }
    @Override public ConfigSource create(ConfigOptions options) { return new EtcdSource(options); }

    static final class EtcdSource implements ConfigSource {
        private final List<String> resources;
        private final long timeoutMillis;
        private final ClientAdapter client;
        private final Map<String, Map<String, String>> cache = new LinkedHashMap<>();
        private final List<Watch.Watcher> watchers = new ArrayList<>();

        EtcdSource(ConfigOptions options) {
            if (options.endpoint().isBlank()) throw new IllegalArgumentException("etcd endpoint must not be blank");
            resources = options.resources();
            timeoutMillis = Long.parseLong(options.property("timeoutMillis", "3000"));
            if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
            String[] endpoints = java.util.Arrays.stream(options.endpoint().split(","))
                    .map(String::trim).filter(value -> !value.isEmpty()).toArray(String[]::new);
            var builder = Client.builder().endpoints(endpoints);
            String username = options.properties().get("username");
            String password = options.properties().get("password");
            if (username != null && !username.isBlank()) builder.user(bytes(username));
            if (password != null) builder.password(bytes(password));
            client = new JetcdAdapter(builder.build());
        }

        EtcdSource(ConfigOptions options, ClientAdapter client) {
            resources = options.resources();
            timeoutMillis = Long.parseLong(options.property("timeoutMillis", "3000"));
            if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
            this.client = client;
        }

        @Override public synchronized Map<String, String> load() throws Exception {
            for (String resource : resources) {
                cache.put(resource, PropertiesDocument.parse(client.get(resource, timeoutMillis)));
            }
            return merged();
        }

        @Override public synchronized AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                                           Consumer<Throwable> onError) {
            stopWatching();
            for (String resource : resources) {
                Watch.Watcher watcher = client.watch(resource, content -> {
                    try {
                        Map<String, String> latest;
                        synchronized (EtcdSource.this) {
                            cache.put(resource, PropertiesDocument.parse(content));
                            latest = merged();
                        }
                        onUpdate.accept(latest);
                    } catch (Throwable e) { onError.accept(e); }
                }, onError);
                watchers.add(watcher);
            }
            return this::stopWatching;
        }

        private Map<String, String> merged() {
            Map<String, String> result = new LinkedHashMap<>();
            resources.forEach(resource -> result.putAll(cache.getOrDefault(resource, Map.of())));
            return Map.copyOf(result);
        }

        private synchronized void stopWatching() {
            watchers.forEach(Watch.Watcher::close);
            watchers.clear();
        }

        @Override public void close() {
            stopWatching();
            client.close();
        }
    }

    interface ClientAdapter extends AutoCloseable {
        String get(String key, long timeoutMillis) throws Exception;
        Watch.Watcher watch(String key, Consumer<String> update, Consumer<Throwable> error);
        @Override void close();
    }

    private static ByteSequence bytes(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }

    static final class JetcdAdapter implements ClientAdapter {
        private final Client client;
        JetcdAdapter(Client client) { this.client = client; }

        @Override public String get(String key, long timeoutMillis) throws Exception {
            var response = client.getKVClient().get(bytes(key)).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return response.getKvs().isEmpty() ? "" :
                    response.getKvs().getFirst().getValue().toString(StandardCharsets.UTF_8);
        }

        @Override public Watch.Watcher watch(String key, Consumer<String> update, Consumer<Throwable> error) {
            return client.getWatchClient().watch(bytes(key), Watch.listener(response -> {
                for (WatchEvent event : response.getEvents()) {
                    update.accept(event.getEventType() == WatchEvent.EventType.PUT
                            ? event.getKeyValue().getValue().toString(StandardCharsets.UTF_8) : "");
                }
            }, error::accept));
        }

        @Override public void close() { client.close(); }
    }
}
