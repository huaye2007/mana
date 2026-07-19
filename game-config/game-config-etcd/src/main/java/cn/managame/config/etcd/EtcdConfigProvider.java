package cn.managame.config.etcd;

import cn.managame.config.ConfigOptions;
import cn.managame.config.spi.ConfigData;
import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;
import cn.managame.config.support.PropertiesDocument;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.WatchOption;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

public final class EtcdConfigProvider implements ConfigProvider {
    @Override public String type() { return "etcd"; }
    @Override public ConfigSource create(ConfigOptions options) { return new EtcdSource(options); }

    static final class EtcdSource implements ConfigSource {
        private final List<String> resources;
        private final long timeoutMillis;
        private final ClientAdapter client;
        private final AtomicLong latestRevision = new AtomicLong(ConfigData.UNVERSIONED);
        private final List<Watch.Watcher> watchers = new ArrayList<>();
        private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("game-config-etcd-", 0).factory());

        EtcdSource(ConfigOptions options) {
            if (options.endpoint().isBlank()) throw new IllegalArgumentException("etcd endpoint must not be blank");
            resources = options.resources();
            timeoutMillis = parseTimeout(options);
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
            timeoutMillis = parseTimeout(options);
            this.client = client;
        }

        private static long parseTimeout(ConfigOptions options) {
            long timeoutMillis = Long.parseLong(options.property("timeoutMillis", "3000"));
            if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
            return timeoutMillis;
        }

        @Override public Map<String, String> load() throws Exception {
            return loadData().values();
        }

        @Override public ConfigData loadData() throws Exception {
            VersionedContents contents = client.getAll(resources, 0, timeoutMillis);
            latestRevision.accumulateAndGet(contents.revision(), Math::max);
            return parse(contents);
        }

        private ConfigData parse(VersionedContents contents) {
            Map<String, String> merged = new LinkedHashMap<>();
            resources.forEach(resource -> merged.putAll(PropertiesDocument.parse(
                    contents.values().getOrDefault(resource, ""))));
            return new ConfigData(contents.revision(), merged);
        }

        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                             Consumer<Throwable> onError) {
            return watchData(data -> onUpdate.accept(data.values()), onError);
        }

        @Override public synchronized AutoCloseable watchData(Consumer<ConfigData> onUpdate,
                                                               Consumer<Throwable> onError) {
            if (!watchers.isEmpty()) throw new IllegalStateException("Etcd config watch is already active");
            long startRevision = Math.max(1, latestRevision.get() + 1);
            try {
                for (String resource : resources) {
                    Watch.Watcher watcher = client.watch(resource, startRevision,
                            revision -> submitRefresh(revision, onUpdate, onError), onError);
                    watchers.add(watcher);
                }
            } catch (RuntimeException | Error error) {
                stopWatching();
                throw error;
            }
            return this::stopWatching;
        }

        private void submitRefresh(long revision, Consumer<ConfigData> onUpdate, Consumer<Throwable> onError) {
            try {
                eventExecutor.execute(() -> refresh(revision, onUpdate, onError));
            } catch (RejectedExecutionException ignored) {
                // Source is closing.
            }
        }

        private void refresh(long revision, Consumer<ConfigData> onUpdate, Consumer<Throwable> onError) {
            if (revision <= latestRevision.get()) return;
            try {
                VersionedContents contents = client.getAll(resources, revision, timeoutMillis);
                long previous = latestRevision.getAndAccumulate(contents.revision(), Math::max);
                if (contents.revision() > previous) onUpdate.accept(parse(contents));
            } catch (Throwable error) {
                onError.accept(error);
            }
        }

        private synchronized void stopWatching() {
            watchers.forEach(Watch.Watcher::close);
            watchers.clear();
        }

        @Override public void close() {
            stopWatching();
            eventExecutor.shutdownNow();
            client.close();
        }
    }

    record VersionedContents(long revision, Map<String, String> values) {
        VersionedContents {
            if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
            values = Map.copyOf(values);
        }
    }

    interface ClientAdapter extends AutoCloseable {
        VersionedContents getAll(List<String> keys, long revision, long timeoutMillis) throws Exception;
        Watch.Watcher watch(String key, long startRevision, LongConsumer update, Consumer<Throwable> error);
        @Override void close();
    }

    private static ByteSequence bytes(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }

    static final class JetcdAdapter implements ClientAdapter {
        private final Client client;
        JetcdAdapter(Client client) { this.client = client; }

        @Override public VersionedContents getAll(List<String> keys, long requestedRevision,
                                                   long timeoutMillis) throws Exception {
            Map<String, String> values = new LinkedHashMap<>();
            long revision = requestedRevision;
            for (int index = 0; index < keys.size(); index++) {
                String key = keys.get(index);
                var future = revision == 0 && index == 0
                        ? client.getKVClient().get(bytes(key))
                        : client.getKVClient().get(bytes(key), GetOption.builder().withRevision(revision).build());
                var response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                if (revision == 0) revision = response.getHeader().getRevision();
                String content = response.getKvs().isEmpty() ? ""
                        : response.getKvs().getFirst().getValue().toString(StandardCharsets.UTF_8);
                values.put(key, content);
            }
            return new VersionedContents(revision, values);
        }

        @Override public Watch.Watcher watch(String key, long startRevision, LongConsumer update,
                                              Consumer<Throwable> error) {
            WatchOption option = WatchOption.builder().withRevision(startRevision).build();
            return client.getWatchClient().watch(bytes(key), option, Watch.listener(response -> {
                if (!response.getEvents().isEmpty()) update.accept(response.getHeader().getRevision());
            }, error::accept));
        }

        @Override public void close() { client.close(); }
    }
}
