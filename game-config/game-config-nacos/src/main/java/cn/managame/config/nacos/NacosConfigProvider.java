package cn.managame.config.nacos;

import cn.managame.config.ConfigException;
import cn.managame.config.ConfigOptions;
import cn.managame.config.spi.ConfigData;
import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;
import cn.managame.config.support.ConfigRevisions;
import cn.managame.config.support.PropertiesDocument;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class NacosConfigProvider implements ConfigProvider {
    @Override public String type() { return "nacos"; }
    @Override public ConfigSource create(ConfigOptions options) { return new NacosSource(options); }

    static final class NacosSource implements ConfigSource {
        private final List<Resource> resources;
        private final long timeoutMillis;
        private final String revisionKey;
        private final ConfigService service;
        private final ExecutorService executor;
        private final List<Registration> registrations = new ArrayList<>();
        private long contentRevision;
        private Map<String, String> lastLoadedValues = Map.of();

        NacosSource(ConfigOptions options) {
            Settings settings = Settings.parse(options);
            resources = settings.resources();
            timeoutMillis = settings.timeoutMillis();
            revisionKey = options.property("revisionKey", "_revision").trim();
            service = createService(options);
            executor = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("game-config-nacos-", 0).factory());
        }

        NacosSource(ConfigOptions options, ConfigService service) {
            Settings settings = Settings.parse(options);
            resources = settings.resources();
            timeoutMillis = settings.timeoutMillis();
            revisionKey = options.property("revisionKey", "_revision").trim();
            this.service = service;
            executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("game-config-nacos-", 0).factory());
        }

        private static ConfigService createService(ConfigOptions options) {
            if (options.endpoint().isBlank()) throw new IllegalArgumentException("nacos endpoint must not be blank");
            Properties properties = new Properties();
            properties.putAll(options.properties());
            properties.remove("group");
            properties.remove("timeoutMillis");
            properties.remove("revisionKey");
            properties.setProperty("serverAddr", options.endpoint());
            try { return NacosFactory.createConfigService(properties); }
            catch (Exception e) { throw new ConfigException("cannot create Nacos config service", e); }
        }

        @Override public Map<String, String> load() throws Exception {
            return loadData().values();
        }

        @Override public synchronized ConfigData loadData() throws Exception {
            Map<Resource, Map<String, String>> refreshed = new LinkedHashMap<>();
            List<Map<String, String>> documents = new ArrayList<>(resources.size());
            for (Resource resource : resources) {
                Map<String, String> document = PropertiesDocument.parse(
                        service.getConfig(resource.dataId(), resource.group(), timeoutMillis));
                refreshed.put(resource, document);
                documents.add(document);
            }
            if (!"UP".equalsIgnoreCase(service.getServerStatus())) {
                throw new ConfigException("Nacos config server is not reachable");
            }
            boolean multipleResources = resources.size() > 1;
            long revision;
            if (multipleResources) {
                revision = ConfigRevisions.commonRevision(documents, revisionKey);
            } else {
                Map<String, String> values = merged(refreshed, null);
                if (!lastLoadedValues.equals(values)) {
                    lastLoadedValues = values;
                    contentRevision++;
                }
                revision = contentRevision;
            }
            Map<String, String> values = merged(refreshed, multipleResources ? revisionKey : null);
            return new ConfigData(revision, values);
        }

        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                             Consumer<Throwable> onError) throws Exception {
            return watchData(data -> onUpdate.accept(data.values()), onError);
        }

        @Override public synchronized AutoCloseable watchData(Consumer<ConfigData> onUpdate,
                                                               Consumer<Throwable> onError) throws Exception {
            if (!registrations.isEmpty()) throw new IllegalStateException("Nacos config watch is already active");
            try {
                for (Resource resource : resources) {
                    Listener listener = new Listener() {
                        @Override public Executor getExecutor() { return executor; }
                        @Override public void receiveConfigInfo(String ignored) {
                            try {
                                ConfigData latest = loadData();
                                onUpdate.accept(latest);
                            } catch (Throwable e) { onError.accept(e); }
                        }
                    };
                    service.addListener(resource.dataId(), resource.group(), listener);
                    registrations.add(new Registration(resource, listener));
                }
            } catch (Exception e) {
                stopWatching();
                throw e;
            }
            return this::stopWatching;
        }

        private Map<String, String> merged(Map<Resource, Map<String, String>> source, String excludedKey) {
            Map<String, String> result = new LinkedHashMap<>();
            resources.forEach(resource -> source.getOrDefault(resource, Map.of()).forEach((key, value) -> {
                if (!key.equals(excludedKey)) result.put(key, value);
            }));
            return Map.copyOf(result);
        }

        private synchronized void stopWatching() {
            registrations.forEach(registration -> service.removeListener(
                    registration.resource().dataId(), registration.resource().group(), registration.listener()));
            registrations.clear();
        }

        @Override public void close() throws Exception {
            stopWatching();
            executor.shutdownNow();
            service.shutDown();
        }
    }

    record Resource(String group, String dataId) {
        static Resource parse(String value, String defaultGroup) {
            int separator = value.indexOf(':');
            String group = separator < 0 ? defaultGroup : value.substring(0, separator).trim();
            String dataId = separator < 0 ? value.trim() : value.substring(separator + 1).trim();
            if (group.isBlank() || dataId.isBlank()) throw new IllegalArgumentException("invalid Nacos resource: " + value);
            return new Resource(group, dataId);
        }
    }
    record Settings(List<Resource> resources, long timeoutMillis) {
        static Settings parse(ConfigOptions options) {
            String defaultGroup = options.property("group", "DEFAULT_GROUP");
            List<Resource> resources = options.resources().stream()
                    .map(value -> Resource.parse(value, defaultGroup)).toList();
            long timeoutMillis = Long.parseLong(options.property("timeoutMillis", "3000"));
            if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
            return new Settings(resources, timeoutMillis);
        }
    }
    record Registration(Resource resource, Listener listener) { }
}
