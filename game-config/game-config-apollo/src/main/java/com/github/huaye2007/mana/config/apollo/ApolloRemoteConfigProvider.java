package com.github.huaye2007.mana.config.apollo;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class ApolloRemoteConfigProvider implements RemoteConfigProvider {
    private static final Logger log = LoggerFactory.getLogger(ApolloRemoteConfigProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_MS = 3000L;
    private static final long DEFAULT_LONG_POLL_TIMEOUT_MS = 60000L;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000L;

    private final ConcurrentHashMap<String, Map<String, String>> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> releaseKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> notificationIds = new ConcurrentHashMap<>();
    private final ApolloHttpTransport injectedTransport;
    private final Object watchLock = new Object();
    private final AtomicLong watchGeneration = new AtomicLong();
    private volatile ApolloHttpTransport transport;
    private volatile List<NamespaceEntry> entries = List.of();
    private volatile Thread watchThread;
    private volatile boolean watching;

    public ApolloRemoteConfigProvider() {
        this(null);
    }

    ApolloRemoteConfigProvider(ApolloHttpTransport transport) {
        this.injectedTransport = transport;
        this.transport = transport;
    }

    @Override
    public String type() {
        return "apollo";
    }

    @Override
    public boolean supportsPush() {
        return true;
    }

    @Override
    public Map<String, String> load(Properties remoteProperties) throws Exception {
        Properties props = safe(remoteProperties);
        long timeoutMs = parsePositiveLong(props, "timeoutMs", DEFAULT_TIMEOUT_MS);
        List<NamespaceEntry> resolvedEntries = resolveEntries(props);
        ApolloHttpTransport activeTransport = getOrCreateTransport(props);

        Map<String, String> merged = new HashMap<>();
        for (NamespaceEntry entry : resolvedEntries) {
            LoadedNamespace loaded = loadNamespace(activeTransport, props, entry, timeoutMs);
            cache.put(entry.cacheKey(), loaded.config());
            if (loaded.releaseKey() != null && !loaded.releaseKey().isBlank()) {
                releaseKeys.put(entry.cacheKey(), loaded.releaseKey());
            }
            merged.putAll(loaded.config());
        }
        this.entries = resolvedEntries;
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
        List<NamespaceEntry> resolvedEntries = resolveEntries(props);
        ApolloHttpTransport activeTransport = getOrCreateTransport(props);
        Map<String, String> initialSnapshot = load(props);
        callback.accept(initialSnapshot);

        synchronized (watchLock) {
            stopWatchThread();
            long generation = watchGeneration.incrementAndGet();
            watching = true;
            Thread thread = new Thread(
                    () -> watchLoop(activeTransport, props, resolvedEntries, generation, callback, errorCallback),
                    "apollo-config-long-poll");
            thread.setDaemon(true);
            watchThread = thread;
            thread.start();
        }
    }

    @Override
    public void close() {
        synchronized (watchLock) {
            stopWatchThread();
        }
        ApolloHttpTransport activeTransport = this.transport;
        if (activeTransport != null && activeTransport != injectedTransport) {
            try {
                activeTransport.close();
            } catch (Exception e) {
                log.warn("Failed to close Apollo HTTP transport", e);
            }
            this.transport = null;
        }
    }

    private void watchLoop(ApolloHttpTransport activeTransport,
                           Properties props,
                           List<NamespaceEntry> watchedEntries,
                           long generation,
                           Consumer<Map<String, String>> callback,
                           Consumer<Exception> errorCallback) {
        long timeoutMs = parsePositiveLong(props, "timeoutMs", DEFAULT_TIMEOUT_MS);
        long longPollTimeoutMs = parsePositiveLong(props, "longPollTimeoutMs", DEFAULT_LONG_POLL_TIMEOUT_MS);
        long retryDelayMs = parseNonNegativeLong(props, "retryDelayMs", DEFAULT_RETRY_DELAY_MS);
        while (isCurrentWatch(generation)) {
            try {
                URI uri = buildNotificationsUri(props, watchedEntries);
                ApolloHttpResult result = activeTransport.get(
                        uri,
                        longPollTimeoutMs + timeoutMs,
                        headers(props, uri, watchedEntries.get(0).appId()));
                if (!isCurrentWatch(generation)) {
                    return;
                }
                if (result.statusCode() == 304) {
                    continue;
                }
                if (result.statusCode() < 200 || result.statusCode() >= 300) {
                    throw new ConfigOperationException("Apollo notifications request failed with status "
                            + result.statusCode());
                }
                List<String> changedNamespaces = parseNotifications(result.body(), watchedEntries);
                if (changedNamespaces.isEmpty()) {
                    continue;
                }
                if (!reloadChangedNamespaces(activeTransport, props, watchedEntries,
                        changedNamespaces, timeoutMs, generation)) {
                    return;
                }
                Map<String, String> snapshot = mergeFromCache(watchedEntries);
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

    private boolean reloadChangedNamespaces(ApolloHttpTransport activeTransport,
                                            Properties props,
                                            List<NamespaceEntry> watchedEntries,
                                            List<String> changedNamespaces,
                                            long timeoutMs,
                                            long generation) throws Exception {
        for (NamespaceEntry entry : watchedEntries) {
            if (!changedNamespaces.contains(entry.namespace())) {
                continue;
            }
            LoadedNamespace loaded = loadNamespace(activeTransport, props, entry, timeoutMs);
            synchronized (watchLock) {
                if (!isCurrentWatch(generation)) {
                    return false;
                }
                cache.put(entry.cacheKey(), loaded.config());
                if (loaded.releaseKey() != null && !loaded.releaseKey().isBlank()) {
                    releaseKeys.put(entry.cacheKey(), loaded.releaseKey());
                }
            }
        }
        return true;
    }

    private LoadedNamespace loadNamespace(ApolloHttpTransport activeTransport,
                                          Properties props,
                                          NamespaceEntry entry,
                                          long timeoutMs) throws Exception {
        URI uri = buildConfigUri(props, entry);
        ApolloHttpResult result = activeTransport.get(uri, timeoutMs, headers(props, uri, entry.appId()));
        if (result.statusCode() == 304) {
            return new LoadedNamespace(cache.getOrDefault(entry.cacheKey(), Map.of()), releaseKeys.get(entry.cacheKey()));
        }
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new ConfigOperationException("Apollo config request failed for namespace "
                    + entry.namespace() + " with status " + result.statusCode());
        }
        return parseConfigResponse(result.body());
    }

    private LoadedNamespace parseConfigResponse(String body) {
        if (body == null || body.isBlank()) {
            return new LoadedNamespace(Map.of(), null);
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            return new LoadedNamespace(PropertyParser.parse(body), null);
        }
        try {
            JsonNode root = JSON.readTree(trimmed);
            JsonNode configurations = root.get("configurations");
            Map<String, String> result = new HashMap<>();
            if (configurations != null && configurations.isObject()) {
                for (Map.Entry<String, JsonNode> field : configurations.properties()) {
                    result.put(field.getKey(), stringify(field.getValue()));
                }
            } else {
                flattenJson("", root, result);
            }
            JsonNode releaseKeyNode = root.get("releaseKey");
            String releaseKey = releaseKeyNode == null || releaseKeyNode.isNull() ? null : releaseKeyNode.asText();
            return new LoadedNamespace(Map.copyOf(result), releaseKey);
        } catch (JsonProcessingException e) {
            throw new ConfigOperationException("Failed to parse Apollo config response", e);
        }
    }

    private List<String> parseNotifications(String body, List<NamespaceEntry> watchedEntries) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isArray()) {
                return watchedEntries.stream().map(NamespaceEntry::namespace).toList();
            }
            List<String> changed = new ArrayList<>();
            for (JsonNode item : root) {
                JsonNode namespaceNode = item.get("namespaceName");
                if (namespaceNode == null || namespaceNode.asText().isBlank()) {
                    continue;
                }
                String namespace = namespaceNode.asText();
                changed.add(namespace);
                JsonNode notificationIdNode = item.get("notificationId");
                if (notificationIdNode != null && notificationIdNode.isIntegralNumber()) {
                    long notificationId = notificationIdNode.asLong();
                    for (NamespaceEntry entry : watchedEntries) {
                        if (entry.namespace().equals(namespace)) {
                            notificationIds.put(entry.cacheKey(), notificationId);
                        }
                    }
                }
            }
            return changed;
        } catch (JsonProcessingException e) {
            throw new ConfigOperationException("Failed to parse Apollo notification response", e);
        }
    }

    private void flattenJson(String prefix, JsonNode node, Map<String, String> result) throws JsonProcessingException {
        if (node == null || node.isNull()) {
            if (!prefix.isEmpty()) {
                result.put(prefix, "");
            }
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String childPrefix = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                flattenJson(childPrefix, field.getValue(), result);
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String childPrefix = prefix.isEmpty() ? String.valueOf(i) : prefix + "." + i;
                flattenJson(childPrefix, node.get(i), result);
            }
            return;
        }
        if (!prefix.isEmpty()) {
            result.put(prefix, stringify(node));
        }
    }

    private String stringify(JsonNode node) throws JsonProcessingException {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isValueNode()) {
            return node.asText();
        }
        return JSON.writeValueAsString(node);
    }

    private URI buildConfigUri(Properties props, NamespaceEntry entry) {
        String baseUrl = normalizeBaseUrl(props.getProperty("configServiceUrl",
                props.getProperty("serverAddr", "http://localhost:8080")));
        StringBuilder uri = new StringBuilder(baseUrl)
                .append("/configs/")
                .append(pathSegment(entry.appId()))
                .append('/')
                .append(pathSegment(entry.cluster()))
                .append('/')
                .append(pathSegment(entry.namespace()));
        List<String> query = new ArrayList<>();
        addQuery(query, "ip", props.getProperty("ip"));
        addQuery(query, "label", props.getProperty("label"));
        String releaseKey = releaseKeys.get(entry.cacheKey());
        if (releaseKey != null && !releaseKey.isBlank()) {
            addQuery(query, "releaseKey", releaseKey);
        }
        appendQuery(uri, query);
        return URI.create(uri.toString());
    }

    private URI buildNotificationsUri(Properties props, List<NamespaceEntry> watchedEntries) throws JsonProcessingException {
        String baseUrl = normalizeBaseUrl(props.getProperty("configServiceUrl",
                props.getProperty("serverAddr", "http://localhost:8080")));
        StringBuilder uri = new StringBuilder(baseUrl).append("/notifications/v2");
        List<Map<String, Object>> notifications = new ArrayList<>();
        for (NamespaceEntry entry : watchedEntries) {
            notifications.add(Map.of(
                    "namespaceName", entry.namespace(),
                    "notificationId", notificationIds.getOrDefault(entry.cacheKey(), -1L)));
        }
        List<String> query = new ArrayList<>();
        addQuery(query, "appId", watchedEntries.get(0).appId());
        addQuery(query, "cluster", watchedEntries.get(0).cluster());
        addQuery(query, "notifications", JSON.writeValueAsString(notifications));
        appendQuery(uri, query);
        return URI.create(uri.toString());
    }

    private Map<String, String> headers(Properties props, URI uri, String appId) {
        String secret = resolveAccessKeySecret(props);
        if (secret == null) {
            return Map.of();
        }
        long timestamp = System.currentTimeMillis();
        String pathWithQuery = uri.getRawPath();
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            pathWithQuery += "?" + uri.getRawQuery();
        }
        String signature = sign(secret, timestamp + "\n" + pathWithQuery);
        return Map.of(
                "Authorization", "Apollo " + appId + ":" + signature,
                "Timestamp", String.valueOf(timestamp));
    }

    private String resolveAccessKeySecret(Properties props) {
        String envName = firstNonBlank(props.getProperty("accessKeySecretEnv"), props.getProperty("secretEnv"));
        if (envName != null) {
            String envValue = System.getenv(envName);
            if (envValue != null && !envValue.isBlank()) {
                return envValue.trim();
            }
        }
        return firstNonBlank(
                props.getProperty("accessKeySecret"),
                props.getProperty("apolloAccessKeySecret"),
                props.getProperty("secret"));
    }

    private String sign(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ConfigOperationException("Failed to sign Apollo request", e);
        }
    }

    private List<NamespaceEntry> resolveEntries(Properties props) {
        String appId = props.getProperty("appId");
        if (appId == null || appId.isBlank()) {
            throw new ConfigOperationException("Apollo config requires appId");
        }
        String cluster = props.getProperty("cluster", props.getProperty("clusterName", "default")).trim();
        String namespaceList = firstNonBlank(
                props.getProperty("namespaces"),
                props.getProperty("namespace"),
                props.getProperty("namespaceName"),
                props.getProperty("dataIds"),
                props.getProperty("dataId"));
        if (namespaceList == null) {
            namespaceList = "application";
        }
        List<NamespaceEntry> result = new ArrayList<>();
        for (String segment : namespaceList.split(",")) {
            String namespace = segment.trim();
            if (!namespace.isEmpty()) {
                result.add(new NamespaceEntry(appId.trim(), cluster, namespace));
            }
        }
        if (result.isEmpty()) {
            throw new ConfigOperationException("Apollo config requires namespace or namespaces");
        }
        return result;
    }

    private Map<String, String> mergeFromCache(List<NamespaceEntry> watchedEntries) {
        Map<String, String> merged = new HashMap<>();
        for (NamespaceEntry entry : watchedEntries) {
            Map<String, String> part = cache.get(entry.cacheKey());
            if (part != null) {
                merged.putAll(part);
            }
        }
        return Map.copyOf(merged);
    }

    private ApolloHttpTransport getOrCreateTransport(Properties props) {
        ApolloHttpTransport activeTransport = this.transport;
        if (activeTransport == null) {
            synchronized (this) {
                activeTransport = this.transport;
                if (activeTransport == null) {
                    long connectTimeoutMs = parsePositiveLong(props, "connectTimeoutMs", DEFAULT_TIMEOUT_MS);
                    activeTransport = new JdkApolloHttpTransport(connectTimeoutMs);
                    this.transport = activeTransport;
                }
            }
        }
        return activeTransport;
    }

    private void stopWatchThread() {
        watchGeneration.incrementAndGet();
        watching = false;
        Thread thread = watchThread;
        watchThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private boolean isCurrentWatch(long generation) {
        return watching
                && generation == watchGeneration.get()
                && Thread.currentThread() == watchThread;
    }

    private void reportPushError(Consumer<Exception> errorCallback, Exception error) {
        if (errorCallback == null) {
            log.warn("Apollo config long poll failed", error);
            return;
        }
        try {
            errorCallback.accept(error);
        } catch (RuntimeException handlerError) {
            log.warn("Apollo config push error handler threw exception", handlerError);
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
        String base = value == null || value.isBlank() ? "http://localhost:8080" : value.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
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

    private String pathSegment(String value) {
        return queryEncode(value).replace("+", "%20");
    }

    private String queryEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record ApolloHttpResult(int statusCode, String body, Map<String, List<String>> headers) {
    }

    interface ApolloHttpTransport extends AutoCloseable {
        ApolloHttpResult get(URI uri, long timeoutMillis, Map<String, String> headers) throws Exception;

        @Override
        default void close() {
        }
    }

    private static final class JdkApolloHttpTransport implements ApolloHttpTransport {
        private final HttpClient client;

        private JdkApolloHttpTransport(long connectTimeoutMs) {
            this.client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
        }

        @Override
        public ApolloHttpResult get(URI uri, long timeoutMillis, Map<String, String> headers)
                throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .GET();
            headers.forEach(builder::header);
            HttpResponse<String> response = client.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new ApolloHttpResult(response.statusCode(), response.body(), response.headers().map());
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private record NamespaceEntry(String appId, String cluster, String namespace) {
        String cacheKey() {
            return appId + "@@" + cluster + "@@" + namespace;
        }
    }

    private record LoadedNamespace(Map<String, String> config, String releaseKey) {
    }
}
