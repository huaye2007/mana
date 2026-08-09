package cn.managame.config.nacos;

import cn.managame.config.ConfigException;
import cn.managame.config.ConfigLayer;
import cn.managame.config.spi.ConfigFormat;
import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;
import cn.managame.config.support.ConfigFormats;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Reads config from Nacos, one {@code group:dataId} per resource.
 *
 * <p>Layer properties: {@code group} (default {@code DEFAULT_GROUP}) supplies the group for resources
 * written without one, {@code timeoutMillis} (default {@code 3000}), and {@code format} pins the
 * document format. Anything else is handed to the Nacos client, for example {@code namespace},
 * {@code username} and {@code password}.</p>
 *
 * <p>Nacos hands the new document to the listener, so an update republishes from a per-resource cache
 * without going back to the server. A change to one dataId therefore costs no extra requests, where
 * re-reading the whole layer used to cost one request per resource per callback.</p>
 */
public final class NacosConfigProvider implements ConfigProvider {
    @Override public String type() { return "nacos"; }
    @Override public ConfigSource create(ConfigLayer layer) { return new NacosSource(layer); }

    static final class NacosSource implements ConfigSource {
        private final List<Resource> resources;
        private final Map<Resource, ConfigFormat> formats;
        private final Map<Resource, Map<String, String>> parsed = new ConcurrentHashMap<>();
        private final long timeoutMillis;
        private final ConfigService service;
        private final ExecutorService executor;
        private final List<Registration> registrations = new ArrayList<>();

        NacosSource(ConfigLayer layer) {
            this(layer, createService(layer));
        }

        NacosSource(ConfigLayer layer, ConfigService service) {
            Settings settings = Settings.parse(layer);
            resources = settings.resources();
            formats = settings.formats();
            timeoutMillis = settings.timeoutMillis();
            this.service = service;
            executor = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("game-config-nacos-", 0).factory());
        }

        private static ConfigService createService(ConfigLayer layer) {
            Properties properties = new Properties();
            properties.putAll(layer.properties());
            properties.remove("group");
            properties.remove("timeoutMillis");
            properties.remove(ConfigFormats.FORMAT_PROPERTY);
            properties.setProperty("serverAddr", layer.requireEndpoint());
            try { return NacosFactory.createConfigService(properties); }
            catch (Exception e) { throw new ConfigException("cannot create Nacos config service", e); }
        }

        @Override public Map<String, String> load() throws Exception {
            for (Resource resource : resources) {
                store(resource, service.getConfig(resource.dataId(), resource.group(), timeoutMillis));
            }
            if (!"UP".equalsIgnoreCase(service.getServerStatus())) {
                throw new ConfigException("Nacos config server is not reachable");
            }
            return merged();
        }

        /** The client tracks server reachability locally, so liveness needs no request. */
        @Override public void ping() {
            if (!"UP".equalsIgnoreCase(service.getServerStatus())) {
                throw new ConfigException("Nacos config server is not reachable");
            }
        }

        @Override public synchronized AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                                          Consumer<Throwable> onError) throws Exception {
            if (!registrations.isEmpty()) throw new IllegalStateException("Nacos config watch is already active");
            try {
                for (Resource resource : resources) {
                    Listener listener = new Listener() {
                        @Override public Executor getExecutor() { return executor; }
                        @Override public void receiveConfigInfo(String content) {
                            try {
                                // The callback carries the new document; re-reading every resource here
                                // would turn one publish into one request per resource.
                                store(resource, content);
                                onUpdate.accept(merged());
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

        /** A deleted or empty config is an empty document, never an error. */
        private void store(Resource resource, String content) {
            parsed.put(resource, formats.get(resource).parse(content));
        }

        private Map<String, String> merged() {
            Map<String, String> result = new LinkedHashMap<>();
            resources.forEach(resource -> result.putAll(parsed.getOrDefault(resource, Map.of())));
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

        @Override public String toString() { return "NacosSource" + resources; }
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

    record Settings(List<Resource> resources, Map<Resource, ConfigFormat> formats, long timeoutMillis) {
        static Settings parse(ConfigLayer layer) {
            String defaultGroup = layer.property("group", "DEFAULT_GROUP");
            List<Resource> resources = layer.requireResources().stream()
                    .map(value -> Resource.parse(value, defaultGroup)).toList();
            Map<Resource, ConfigFormat> formats = new LinkedHashMap<>();
            // Format follows the dataId, so app.json and app.properties can share one layer.
            resources.forEach(resource -> formats.put(resource, ConfigFormats.of(layer, resource.dataId())));
            long timeoutMillis = layer.longProperty("timeoutMillis", 3000);
            if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
            return new Settings(resources, Map.copyOf(formats), timeoutMillis);
        }
    }

    record Registration(Resource resource, Listener listener) { }
}
