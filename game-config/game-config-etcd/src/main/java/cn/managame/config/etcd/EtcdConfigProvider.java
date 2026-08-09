package cn.managame.config.etcd;

import cn.managame.config.ConfigLayer;
import cn.managame.config.spi.ConfigData;
import cn.managame.config.spi.ConfigFormat;
import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;
import cn.managame.config.support.ConfigFormats;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.op.Op;
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

/**
 * Reads config from Etcd, one key per resource.
 *
 * <p>All keys are read inside a single transaction, so a multi-key publish is observed whole and the
 * cost of a load is one round trip regardless of how many resources the layer declares.</p>
 *
 * <p>Layer properties: {@code timeoutMillis} (default {@code 3000}), {@code username},
 * {@code password}, and {@code format} to pin the document format of the values.</p>
 */
public final class EtcdConfigProvider implements ConfigProvider {
    @Override public String type() { return "etcd"; }
    @Override public ConfigSource create(ConfigLayer layer) { return new EtcdSource(layer); }

    static final class EtcdSource implements ConfigSource {
        private final List<String> resources;
        private final List<ConfigFormat> formats;
        private final long timeoutMillis;
        private final ClientAdapter client;
        private final AtomicLong latestRevision = new AtomicLong(ConfigData.UNVERSIONED);
        private final List<Watch.Watcher> watchers = new ArrayList<>();
        private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("game-config-etcd-", 0).factory());

        EtcdSource(ConfigLayer layer) {
            resources = layer.requireResources();
            formats = resolveFormats(layer, resources);
            timeoutMillis = parseTimeout(layer);
            String[] endpoints = java.util.Arrays.stream(layer.requireEndpoint().split(","))
                    .map(String::trim).filter(value -> !value.isEmpty()).toArray(String[]::new);
            var builder = Client.builder().endpoints(endpoints);
            String username = layer.property("username", null);
            String password = layer.property("password", null);
            if (username != null && !username.isBlank()) builder.user(bytes(username));
            if (password != null) builder.password(bytes(password));
            client = new JetcdAdapter(builder.build());
        }

        EtcdSource(ConfigLayer layer, ClientAdapter client) {
            resources = layer.requireResources();
            formats = resolveFormats(layer, resources);
            timeoutMillis = parseTimeout(layer);
            this.client = client;
        }

        private static List<ConfigFormat> resolveFormats(ConfigLayer layer, List<String> resources) {
            return resources.stream().map(resource -> ConfigFormats.of(layer, resource)).toList();
        }

        private static long parseTimeout(ConfigLayer layer) {
            long timeoutMillis = layer.longProperty("timeoutMillis", 3000);
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

        /** Reads a header only: no key ranges, no values, so liveness costs nothing on the wire. */
        @Override public void ping() throws Exception {
            client.revision(timeoutMillis);
        }

        private ConfigData parse(VersionedContents contents) {
            Map<String, String> merged = new LinkedHashMap<>();
            for (int index = 0; index < resources.size(); index++) {
                String resource = resources.get(index);
                merged.putAll(formats.get(index).parse(contents.values().getOrDefault(resource, "")));
            }
            return new ConfigData(contents.revision(), merged);
        }

        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                             Consumer<Throwable> onError) {
            return watchData(data -> onUpdate.accept(data.values()), onError);
        }

        @Override public synchronized AutoCloseable watchData(Consumer<ConfigData> onUpdate,
                                                               Consumer<Throwable> onError) {
            if (!watchers.isEmpty()) throw new IllegalStateException("Etcd config watch is already active");
            // Revision 0 means "from now", which is what a watch registered before the first load needs.
            // After a recovery we already know a revision and resume from the one after it.
            long startRevision = Math.max(0, latestRevision.get() + 1);
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
            // Events for keys covered by a read we already did collapse into nothing, so a publish
            // touching every key still costs one read rather than one per key.
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

        @Override public String toString() { return "EtcdSource" + resources; }
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
        /** Current store revision, used as a liveness probe that transfers no keys. */
        long revision(long timeoutMillis) throws Exception;
        @Override void close();
    }

    private static ByteSequence bytes(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }

    static final class JetcdAdapter implements ClientAdapter {
        private final Client client;
        JetcdAdapter(Client client) { this.client = client; }

        /**
         * Reads every key in one transaction.
         *
         * <p>A transaction is served at a single revision, so the result is a consistent cross-key
         * view, and it is one round trip instead of one per key. The previous key-at-a-time loop made
         * startup latency and worst-case timeout scale with the number of resources.</p>
         */
        @Override public VersionedContents getAll(List<String> keys, long requestedRevision,
                                                   long timeoutMillis) throws Exception {
            GetOption option = requestedRevision == 0 ? GetOption.builder().build()
                    : GetOption.builder().withRevision(requestedRevision).build();
            Op[] operations = keys.stream().map(key -> (Op) Op.get(bytes(key), option)).toArray(Op[]::new);
            TxnResponse response = client.getKVClient().txn().Then(operations)
                    .commit().get(timeoutMillis, TimeUnit.MILLISECONDS);
            long revision = requestedRevision == 0 ? response.getHeader().getRevision() : requestedRevision;
            List<GetResponse> results = response.getGetResponses();
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < keys.size(); index++) {
                GetResponse result = results.get(index);
                values.put(keys.get(index), result.getKvs().isEmpty() ? ""
                        : result.getKvs().getFirst().getValue().toString(StandardCharsets.UTF_8));
            }
            return new VersionedContents(revision, values);
        }

        @Override public long revision(long timeoutMillis) throws Exception {
            GetOption option = GetOption.builder().withKeysOnly(true).withCountOnly(true).withLimit(1).build();
            return client.getKVClient().get(bytes("\0"), option)
                    .get(timeoutMillis, TimeUnit.MILLISECONDS)
                    .getHeader().getRevision();
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
