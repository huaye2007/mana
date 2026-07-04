package cn.managame.config.etcd;

import cn.managame.config.exception.ConfigOperationException;
import cn.managame.config.spi.RemoteConfigProvider;
import cn.managame.config.util.PropertyParser;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.watch.WatchEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static cn.managame.config.spi.support.RemoteConfigProperties.parsePositiveLong;
import static cn.managame.config.spi.support.RemoteConfigProperties.safe;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class EtcdRemoteConfigProvider implements RemoteConfigProvider {
    private static final Logger log = LoggerFactory.getLogger(EtcdRemoteConfigProvider.class);

    private final ConcurrentHashMap<String, Map<String, String>> cache = new ConcurrentHashMap<>();
    private final EtcdClientAdapter injectedClientAdapter;
    private volatile List<String> entries;
    private volatile List<CloseableWatcher> watchers = new ArrayList<>();
    private volatile EtcdClientAdapter clientAdapter;

    public EtcdRemoteConfigProvider() {
        this(null);
    }

    EtcdRemoteConfigProvider(EtcdClientAdapter clientAdapter) {
        this.injectedClientAdapter = clientAdapter;
        this.clientAdapter = clientAdapter;
    }

    @Override
    public String type() {
        return "etcd";
    }

    @Override
    public boolean supportsPush() {
        return true;
    }

    @Override
    public Map<String, String> load(Properties remoteProperties) throws Exception {
        Properties props = safe(remoteProperties);
        long timeoutMs = parsePositiveLong(props, "timeoutMs", 3000L);
        List<String> list = resolveEntries(props);
        initClient(props);
        this.entries = list;

        Map<String, String> merged = new HashMap<>();
        for (String dataId : list) {
            String content = clientAdapter.get(dataId, timeoutMs);
            Map<String, String> parsed = PropertyParser.parse(content);
            cache.put(dataId, parsed);
            merged.putAll(parsed);
        }
        return merged;
    }

    @Override
    public void subscribe(Properties remoteProperties, Consumer<Map<String, String>> callback) throws Exception {
        subscribe(remoteProperties, callback, null);
    }

    @Override
    public void subscribe(Properties remoteProperties,
                          Consumer<Map<String, String>> callback,
                          Consumer<Exception> errorCallback) throws Exception {
        Properties props = safe(remoteProperties);
        List<String> list = resolveEntries(props);
        initClient(props);

        // Ensure entries are resolved and initial cache is populated if load was not called yet
        if (this.entries == null || !this.entries.equals(list)) {
            load(props);
        }

        List<CloseableWatcher> newWatchers = new ArrayList<>();
        try {
            for (String dataId : entries) {
                CloseableWatcher watcher = clientAdapter.watch(dataId, content -> {
                    try {
                        cache.put(dataId, PropertyParser.parse(content));
                        callback.accept(mergeFromCache());
                    } catch (RuntimeException e) {
                        log.warn("Failed to handle etcd config push for {}", dataId, e);
                        reportPushError(errorCallback, e);
                    }
                });
                newWatchers.add(watcher);
            }
        } catch (Exception e) {
            closeWatchers(newWatchers);
            throw e;
        }
        List<CloseableWatcher> oldWatchers = this.watchers;
        this.watchers = newWatchers;
        closeWatchers(oldWatchers);

        // 同步回吐一次初始快照，避免 manager start 时再发一轮远端拉取
        callback.accept(mergeFromCache());
    }

    @Override
    public void close() {
        // 关闭所有 watcher
        closeWatchers(watchers);
        watchers.clear();

        // 关闭 etcd client
        EtcdClientAdapter adapter = this.clientAdapter;
        if (adapter != null) {
            try {
                adapter.close();
            } catch (Exception e) {
                log.warn("Failed to close etcd client", e);
            }
            this.clientAdapter = null;
        }
    }

    private void closeWatchers(List<CloseableWatcher> watchersToClose) {
        for (CloseableWatcher watcher : watchersToClose) {
            try {
                watcher.close();
            } catch (Exception e) {
                log.warn("Failed to close etcd watcher", e);
            }
        }
    }

    private synchronized void initClient(Properties remoteProperties) {
        if (clientAdapter == null) {
            String endpointsStr = remoteProperties.getProperty("endpoints", "http://localhost:2379");
            String[] endpoints = splitEndpoints(endpointsStr);
            clientAdapter = injectedClientAdapter == null
                    ? new JetcdClientAdapter(Client.builder().endpoints(endpoints).build())
                    : injectedClientAdapter;
        }
    }

    private String[] splitEndpoints(String endpointsStr) {
        return java.util.Arrays.stream(endpointsStr.split(","))
                .map(String::trim)
                .filter(endpoint -> !endpoint.isEmpty())
                .toArray(String[]::new);
    }

    private Map<String, String> mergeFromCache() {
        Map<String, String> merged = new HashMap<>();
        List<String> list = this.entries;
        if (list == null) {
            return merged;
        }
        for (String dataId : list) {
            Map<String, String> part = cache.get(dataId);
            if (part != null) {
                merged.putAll(part);
            }
        }
        return merged;
    }

    private List<String> resolveEntries(Properties props) {
        String dataIds = props.getProperty("dataIds");
        if (dataIds != null && !dataIds.isBlank()) {
            List<String> result = new ArrayList<>();
            for (String segment : dataIds.split(",")) {
                String trimmed = segment.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            if (result.isEmpty()) {
                throw new ConfigOperationException("Etcd config requires dataId or dataIds");
            }
            return result;
        }
        String dataId = props.getProperty("dataId");
        if (dataId != null && !dataId.isBlank()) {
            return List.of(dataId.trim());
        }
        throw new ConfigOperationException("Etcd config requires dataId or dataIds");
    }

    private void reportPushError(Consumer<Exception> errorCallback, Exception error) {
        if (errorCallback == null) {
            return;
        }
        try {
            errorCallback.accept(error);
        } catch (RuntimeException handlerError) {
            log.warn("Etcd config push error handler threw exception", handlerError);
        }
    }

    interface CloseableWatcher {
        void close();
    }

    interface EtcdClientAdapter {
        String get(String key, long timeoutMs) throws Exception;

        CloseableWatcher watch(String key, Consumer<String> callback) throws Exception;

        void close();
    }

    private static final class JetcdClientAdapter implements EtcdClientAdapter {
        private final Client client;

        private JetcdClientAdapter(Client client) {
            this.client = client;
        }

        @Override
        public String get(String key, long timeoutMs) throws Exception {
            KV kvClient = client.getKVClient();
            GetResponse response = kvClient.get(ByteSequence.from(key, StandardCharsets.UTF_8))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            if (response.getKvs().isEmpty()) {
                return "";
            }
            return response.getKvs().get(0).getValue().toString(StandardCharsets.UTF_8);
        }

        @Override
        public CloseableWatcher watch(String key, Consumer<String> callback) {
            Watch.Watcher watcher = client.getWatchClient().watch(
                    ByteSequence.from(key, StandardCharsets.UTF_8),
                    Watch.listener(response -> {
                        for (WatchEvent event : response.getEvents()) {
                            String content = "";
                            if (event.getEventType() == WatchEvent.EventType.PUT) {
                                content = event.getKeyValue().getValue().toString(StandardCharsets.UTF_8);
                            }
                            callback.accept(content);
                        }
                    }));
            return watcher::close;
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
