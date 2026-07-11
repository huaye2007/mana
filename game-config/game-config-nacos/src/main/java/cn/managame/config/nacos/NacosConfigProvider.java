package cn.managame.config.nacos;

import cn.managame.config.ConfigException;
import cn.managame.config.ConfigOptions;
import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;
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
        private final ConfigService service;
        private final ExecutorService executor;
        private final Map<Resource, Map<String, String>> cache = new LinkedHashMap<>();
        private final List<Registration> registrations = new ArrayList<>();

        NacosSource(ConfigOptions options) {
            this(options, createService(options));
        }

        NacosSource(ConfigOptions options, ConfigService service) {
            String defaultGroup = options.property("group", "DEFAULT_GROUP");
            resources = options.resources().stream().map(value -> Resource.parse(value, defaultGroup)).toList();
            timeoutMillis = Long.parseLong(options.property("timeoutMillis", "3000"));
            if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
            this.service = service;
            executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("game-config-nacos-", 0).factory());
        }

        private static ConfigService createService(ConfigOptions options) {
            if (options.endpoint().isBlank()) throw new IllegalArgumentException("nacos endpoint must not be blank");
            Properties properties = new Properties();
            properties.putAll(options.properties());
            properties.setProperty("serverAddr", options.endpoint());
            try { return NacosFactory.createConfigService(properties); }
            catch (Exception e) { throw new ConfigException("cannot create Nacos config service", e); }
        }

        @Override public synchronized Map<String, String> load() throws Exception {
            for (Resource resource : resources) {
                cache.put(resource, PropertiesDocument.parse(
                        service.getConfig(resource.dataId(), resource.group(), timeoutMillis)));
            }
            return merged();
        }

        @Override public synchronized AutoCloseable watch(Consumer<Map<String, String>> onUpdate,
                                                           Consumer<Throwable> onError) throws Exception {
            stopWatching();
            try {
                for (Resource resource : resources) {
                    Listener listener = new Listener() {
                        @Override public Executor getExecutor() { return executor; }
                        @Override public void receiveConfigInfo(String content) {
                            try {
                                Map<String, String> latest;
                                synchronized (NacosSource.this) {
                                    cache.put(resource, PropertiesDocument.parse(content));
                                    latest = merged();
                                }
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

        private Map<String, String> merged() {
            Map<String, String> result = new LinkedHashMap<>();
            resources.forEach(resource -> result.putAll(cache.getOrDefault(resource, Map.of())));
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
    record Registration(Resource resource, Listener listener) { }
}
