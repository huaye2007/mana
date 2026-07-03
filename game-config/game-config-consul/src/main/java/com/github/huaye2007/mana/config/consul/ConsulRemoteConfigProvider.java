package com.github.huaye2007.mana.config.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.huaye2007.mana.config.exception.ConfigOperationException;
import com.github.huaye2007.mana.config.spi.RemoteConfigProvider;
import com.github.huaye2007.mana.config.util.PropertyParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.huaye2007.mana.config.spi.support.RemoteConfigProperties.copy;
import static com.github.huaye2007.mana.config.spi.support.RemoteConfigProperties.firstNonBlank;
import static com.github.huaye2007.mana.config.spi.support.RemoteConfigProperties.parseNonNegativeLong;
import static com.github.huaye2007.mana.config.spi.support.RemoteConfigProperties.parsePositiveInt;
import static com.github.huaye2007.mana.config.spi.support.RemoteConfigProperties.parsePositiveLong;
import static com.github.huaye2007.mana.config.spi.support.RemoteConfigProperties.safe;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class ConsulRemoteConfigProvider implements RemoteConfigProvider {
    private static final Logger log = LoggerFactory.getLogger(ConsulRemoteConfigProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_MS = 3000L;
    private static final long DEFAULT_WAIT_SECONDS = 55L;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000L;
    private static final int DEFAULT_WATCH_THREADS = 16;

    private final ConcurrentHashMap<String, Map<String, String>> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> indexes = new ConcurrentHashMap<>();
    private final ConsulHttpTransport injectedTransport;
    private final Object watchLock = new Object();
    private final AtomicLong watchGeneration = new AtomicLong();
    private volatile ConsulHttpTransport transport;
    private volatile List<ConsulTarget> targets = List.of();
    private volatile ExecutorService watchExecutor;
    private volatile List<Future<?>> watchTasks = List.of();
    private volatile boolean watching;

    public ConsulRemoteConfigProvider() {
        this(null);
    }

    ConsulRemoteConfigProvider(ConsulHttpTransport transport) {
        this.injectedTransport = transport;
        this.transport = transport;
    }

    @Override
    public String type() {
        return "consul";
    }

    @Override
    public boolean supportsPush() {
        return true;
    }

    @Override
    public Map<String, String> load(Properties remoteProperties) throws Exception {
        Properties props = safe(remoteProperties);
        long timeoutMs = parsePositiveLong(props, "timeoutMs", DEFAULT_TIMEOUT_MS);
        List<ConsulTarget> resolvedTargets = resolveTargets(props);
        ConsulHttpTransport activeTransport = getOrCreateTransport(props);

        Map<String, String> merged = new HashMap<>();
        for (ConsulTarget target : resolvedTargets) {
            LoadedTarget loaded = target.prefix()
                    ? loadPrefix(activeTransport, props, target.value(), 0, timeoutMs)
                    : loadKey(activeTransport, props, target.value(), 0, timeoutMs);
            cache.put(target.cacheKey(), loaded.config());
            if (loaded.index() > 0) {
                indexes.put(target.cacheKey(), loaded.index());
            }
            merged.putAll(loaded.config());
        }
        this.targets = resolvedTargets;
        return Map.copyOf(merged);
    }

    @Override
    public void subscribe(Properties remoteProperties, Consumer<Map<String, String>> callback) throws Exception {
        subscribe(remoteProperties, callback, null);
    }

    @Override
    public void subscribe(Properties remoteProperties,
                          Consumer<Map<String, String>> callback,
                          Consumer<Exception> errorCallback) throws Exception {
        Properties props = copy(safe(remoteProperties));
        List<ConsulTarget> resolvedTargets = resolveTargets(props);
        int watchThreads = parsePositiveInt(props, "watchThreads", DEFAULT_WATCH_THREADS);
        if (resolvedTargets.size() > watchThreads) {
            throw new ConfigOperationException("Consul watch target count " + resolvedTargets.size()
                    + " exceeds watchThreads " + watchThreads
                    + "; use prefix mode or increase watchThreads");
        }
        ConsulHttpTransport activeTransport = getOrCreateTransport(props);
        Map<String, String> initialSnapshot = load(props);
        callback.accept(initialSnapshot);

        synchronized (watchLock) {
            stopWatchThreads();
            long generation = watchGeneration.incrementAndGet();
            watching = true;
            ExecutorService executor = Executors.newFixedThreadPool(
                    watchThreads,
                    new ConsulWatchThreadFactory());
            List<Future<?>> tasks = new ArrayList<>();
            for (ConsulTarget target : resolvedTargets) {
                tasks.add(executor.submit(
                        () -> watchTarget(activeTransport, props, target, resolvedTargets,
                                generation, callback, errorCallback)));
            }
            watchExecutor = executor;
            watchTasks = tasks;
        }
    }

    @Override
    public void close() {
        synchronized (watchLock) {
            stopWatchThreads();
        }
        ConsulHttpTransport activeTransport = this.transport;
        if (activeTransport != null && activeTransport != injectedTransport) {
            try {
                activeTransport.close();
            } catch (Exception e) {
                log.warn("Failed to close Consul HTTP transport", e);
            }
            this.transport = null;
        }
    }

    private void watchTarget(ConsulHttpTransport activeTransport,
                              Properties props,
                              ConsulTarget target,
                              List<ConsulTarget> watchedTargets,
                              long generation,
                              Consumer<Map<String, String>> callback,
                              Consumer<Exception> errorCallback) {
        long timeoutMs = parsePositiveLong(props, "timeoutMs", DEFAULT_TIMEOUT_MS);
        long waitSeconds = parsePositiveLong(props, "waitSeconds", DEFAULT_WAIT_SECONDS);
        long retryDelayMs = parseNonNegativeLong(props, "retryDelayMs", DEFAULT_RETRY_DELAY_MS);
        while (isCurrentWatch(generation)) {
            try {
                long currentIndex = indexes.getOrDefault(target.cacheKey(), 0L);
                LoadedTarget loaded = target.prefix()
                        ? loadPrefix(activeTransport, props, target.value(), currentIndex, timeoutMs + waitSeconds * 1000L)
                        : loadKey(activeTransport, props, target.value(), currentIndex, timeoutMs + waitSeconds * 1000L);
                if (!isCurrentWatch(generation)) {
                    return;
                }
                if (loaded.index() == 0 || loaded.index() == currentIndex) {
                    continue;
                }
                Map<String, String> snapshot;
                synchronized (watchLock) {
                    if (!isCurrentWatch(generation)) {
                        return;
                    }
                    indexes.put(target.cacheKey(), loaded.index());
                    cache.put(target.cacheKey(), loaded.config());
                    snapshot = mergeFromCache(watchedTargets);
                }
                if (!isCurrentWatch(generation)) {
                    return;
                }
                callback.accept(snapshot);
            } catch (Exception e) {
                if (!isCurrentWatch(generation)) {
                    return;
                }
                reportPushError(errorCallback, e);
                sleepQuietly(retryDelayMs);
            }
        }
    }

    private LoadedTarget loadKey(ConsulHttpTransport activeTransport,
                                 Properties props,
                                 String key,
                                 long index,
                                 long timeoutMs) throws Exception {
        ConsulHttpResult result = activeTransport.get(buildKvUri(props, key, false, index), timeoutMs, headers(props));
        long consulIndex = parseConsulIndex(result);
        if (result.statusCode() == 404) {
            return new LoadedTarget(Map.of(), consulIndex);
        }
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new ConfigOperationException("Consul KV request failed for key "
                    + key + " with status " + result.statusCode());
        }
        return new LoadedTarget(PropertyParser.parse(result.body()), consulIndex);
    }

    private LoadedTarget loadPrefix(ConsulHttpTransport activeTransport,
                                    Properties props,
                                    String prefix,
                                    long index,
                                    long timeoutMs) throws Exception {
        ConsulHttpResult result = activeTransport.get(buildKvUri(props, prefix, true, index), timeoutMs, headers(props));
        long consulIndex = parseConsulIndex(result);
        if (result.statusCode() == 404) {
            return new LoadedTarget(Map.of(), consulIndex);
        }
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new ConfigOperationException("Consul KV prefix request failed for prefix "
                    + prefix + " with status " + result.statusCode());
        }
        return new LoadedTarget(parsePrefixResponse(result.body()), consulIndex);
    }

    private Map<String, String> parsePrefixResponse(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isArray()) {
                throw new ConfigOperationException("Consul KV prefix response must be a JSON array");
            }
            List<ConsulKvEntry> entries = new ArrayList<>();
            for (JsonNode node : root) {
                JsonNode keyNode = node.get("Key");
                JsonNode valueNode = node.get("Value");
                if (keyNode == null || keyNode.isNull() || valueNode == null || valueNode.isNull()) {
                    continue;
                }
                String content = new String(Base64.getDecoder().decode(valueNode.asText()), StandardCharsets.UTF_8);
                entries.add(new ConsulKvEntry(keyNode.asText(), content));
            }
            entries.sort(Comparator.comparing(ConsulKvEntry::key));
            Map<String, String> merged = new HashMap<>();
            for (ConsulKvEntry entry : entries) {
                merged.putAll(PropertyParser.parse(entry.content()));
            }
            return Map.copyOf(merged);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new ConfigOperationException("Failed to parse Consul KV prefix response", e);
        }
    }

    private URI buildKvUri(Properties props, String key, boolean recurse, long index) {
        String baseUrl = normalizeBaseUrl(props.getProperty("endpoint",
                props.getProperty("address", "http://127.0.0.1:8500")));
        String normalizedKey = stripLeadingSlash(key);
        StringBuilder uri = new StringBuilder(baseUrl)
                .append("/v1/kv/")
                .append(pathPreservingSlash(normalizedKey));
        List<String> query = new ArrayList<>();
        if (recurse) {
            query.add("recurse=true");
        } else {
            query.add("raw=true");
        }
        addQuery(query, "dc", firstNonBlank(props.getProperty("dc"), props.getProperty("datacenter")));
        if (index > 0) {
            addQuery(query, "index", String.valueOf(index));
            addQuery(query, "wait", parsePositiveLong(props, "waitSeconds", DEFAULT_WAIT_SECONDS) + "s");
        }
        appendQuery(uri, query);
        return URI.create(uri.toString());
    }

    private List<ConsulTarget> resolveTargets(Properties props) {
        String prefix = props.getProperty("prefix");
        if (prefix != null && !prefix.isBlank()) {
            return List.of(new ConsulTarget(stripLeadingSlash(prefix.trim()), true));
        }
        String keys = firstNonBlank(
                props.getProperty("keys"),
                props.getProperty("key"),
                props.getProperty("dataIds"),
                props.getProperty("dataId"));
        if (keys == null) {
            throw new ConfigOperationException("Consul config requires key, keys, or prefix");
        }
        List<ConsulTarget> result = new ArrayList<>();
        for (String segment : keys.split(",")) {
            String key = stripLeadingSlash(segment.trim());
            if (!key.isEmpty()) {
                result.add(new ConsulTarget(key, false));
            }
        }
        if (result.isEmpty()) {
            throw new ConfigOperationException("Consul config requires key, keys, or prefix");
        }
        return result;
    }

    private long parseConsulIndex(ConsulHttpResult result) {
        Optional<String> value = result.firstHeader("X-Consul-Index");
        if (value.isEmpty() || value.get().isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.get().trim());
        } catch (NumberFormatException e) {
            throw new ConfigOperationException("Invalid X-Consul-Index header: " + value.get(), e);
        }
    }

    private Map<String, String> mergeFromCache(List<ConsulTarget> watchedTargets) {
        Map<String, String> merged = new HashMap<>();
        for (ConsulTarget target : watchedTargets) {
            Map<String, String> part = cache.get(target.cacheKey());
            if (part != null) {
                merged.putAll(part);
            }
        }
        return Map.copyOf(merged);
    }

    private Map<String, String> headers(Properties props) {
        String token = firstNonBlank(props.getProperty("token"), props.getProperty("aclToken"));
        if (token == null) {
            return Map.of();
        }
        return Map.of("X-Consul-Token", token);
    }

    private ConsulHttpTransport getOrCreateTransport(Properties props) {
        ConsulHttpTransport activeTransport = this.transport;
        if (activeTransport == null) {
            synchronized (this) {
                activeTransport = this.transport;
                if (activeTransport == null) {
                    long connectTimeoutMs = parsePositiveLong(props, "connectTimeoutMs", DEFAULT_TIMEOUT_MS);
                    activeTransport = new JdkConsulHttpTransport(connectTimeoutMs);
                    this.transport = activeTransport;
                }
            }
        }
        return activeTransport;
    }

    private void stopWatchThreads() {
        watchGeneration.incrementAndGet();
        watching = false;
        for (Future<?> task : watchTasks) {
            task.cancel(true);
        }
        watchTasks = List.of();
        ExecutorService executor = watchExecutor;
        watchExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private boolean isCurrentWatch(long generation) {
        return watching && generation == watchGeneration.get();
    }

    private void reportPushError(Consumer<Exception> errorCallback, Exception error) {
        if (errorCallback == null) {
            log.warn("Consul config blocking query failed", error);
            return;
        }
        try {
            errorCallback.accept(error);
        } catch (RuntimeException handlerError) {
            log.warn("Consul config push error handler threw exception", handlerError);
        }
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String normalizeBaseUrl(String value) {
        String base = value == null || value.isBlank() ? "http://127.0.0.1:8500" : value.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private String stripLeadingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    private void addQuery(List<String> query, String key, String value) {
        if (value != null && !value.isBlank()) {
            query.add(queryParam(key, value.trim()));
        }
    }

    private void appendQuery(StringBuilder uri, List<String> query) {
        if (!query.isEmpty()) {
            uri.append('?').append(String.join("&", query));
        }
    }

    private String queryParam(String key, String value) {
        return queryEncode(key) + "=" + queryEncode(value);
    }

    private String pathPreservingSlash(String value) {
        String[] parts = value.split("/", -1);
        List<String> encoded = new ArrayList<>(parts.length);
        for (String part : parts) {
            encoded.add(queryEncode(part).replace("+", "%20"));
        }
        return String.join("/", encoded);
    }

    private String queryEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record ConsulHttpResult(int statusCode, String body, Map<String, List<String>> headers) {
        Optional<String> firstHeader(String name) {
            String expected = name.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().toLowerCase(Locale.ROOT).equals(expected) && !entry.getValue().isEmpty()) {
                    return Optional.ofNullable(entry.getValue().get(0));
                }
            }
            return Optional.empty();
        }
    }

    interface ConsulHttpTransport extends AutoCloseable {
        ConsulHttpResult get(URI uri, long timeoutMillis, Map<String, String> headers) throws Exception;

        @Override
        default void close() {
        }
    }

    private static final class JdkConsulHttpTransport implements ConsulHttpTransport {
        private final HttpClient client;

        private JdkConsulHttpTransport(long connectTimeoutMs) {
            this.client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
        }

        @Override
        public ConsulHttpResult get(URI uri, long timeoutMillis, Map<String, String> headers)
                throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .GET();
            headers.forEach(builder::header);
            HttpResponse<String> response = client.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new ConsulHttpResult(response.statusCode(), response.body(), response.headers().map());
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private record ConsulTarget(String value, boolean prefix) {
        String cacheKey() {
            return (prefix ? "prefix:" : "key:") + value;
        }
    }

    private record LoadedTarget(Map<String, String> config, long index) {
    }

    private record ConsulKvEntry(String key, String content) {
    }

    private static final class ConsulWatchThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "consul-config-blocking-query-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
