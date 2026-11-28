package cn.managame.ai;

import static cn.managame.FrameworkErrorCodes.AI_CANCELLED;
import static cn.managame.FrameworkErrorCodes.AI_CLOSED;
import static cn.managame.FrameworkErrorCodes.AI_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.AI_PROTOCOL_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.AI_UNAVAILABLE;

import cn.managame.Metadata;
import cn.managame.MetadataKey;
import cn.managame.ai.internal.AiClientMetrics;
import cn.managame.ai.internal.AiTimer;
import cn.managame.ai.internal.SseParser;
import cn.managame.ai.spi.AiDispatcher;
import cn.managame.metrics.Metrics;
import cn.managame.network.connection.WriteStatus;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.util.Timeout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Single non-blocking Netty HTTP/1.1 and W3C SSE client shared across all AI-enabled business modules of a game server:
 *
 * <pre>{@code
 * AiClient ai = AiClient.builder("http://127.0.0.1:8081/api/ai")
 *         .codec(jsonCodec)
 *         .dispatcher(runtimeDispatcher)
 *         .timeout(Duration.ofSeconds(30))
 *         .header("Authorization", () -> "Bearer " + currentToken())
 *         .propagate(TRACE_ID, "X-Trace-Id")
 *         .metrics(metrics)
 *         .build();
 *
 * // Any business module simply calls the single shared AiClient; context validation, Metadata propagation, and route
 * // continuation happen automatically via the configured AiDispatcher:
 * AiCall call = ai.call("/npc/chat", new NpcTalk(roleId, text), NpcReply.class, result -> { ... });
 * }</pre>
 *
 * <p>One {@link AiClient} serves the entire server process over a pooled Netty keep-alive transport and a shared daemon
 * timing wheel covering full end-to-end deadlines (both response headers and response body) for unary calls, SSE
 * streams (with {@code autoRead} TCP backpressure tied to route consumption), and one-way notifications. Callbacks and
 * stream listeners never run on the calling thread or on I/O / timer threads: response decoding happens off the
 * business route before dispatching the decoded result through {@link AiDispatcher}.
 */
public final class AiClient implements AutoCloseable {
    /** Default HTTP header carrying the caller's per-request timeout budget in milliseconds. */
    public static final String TIMEOUT_HEADER = "X-Ai-Timeout-Ms";

    private static final System.Logger LOG = System.getLogger("cn.managame.ai.client");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_IN_FLIGHT = 1024;
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final long DEFAULT_MAX_STREAM_BYTES = 16L * 1024 * 1024;
    private static final int STREAM_HIGH_WATER = 64;
    private static final int STREAM_LOW_WATER = 16;
    private static final int MAX_ERROR_BODY_CHARS = 512;
    private static final byte[] EMPTY_BYTES = {};

    private final String baseUrl;
    private final String basePath;
    private final String hostHeader;
    private final AiCodec codec;
    private final AiDispatcher dispatcher;
    private final Duration defaultTimeout;
    private final String defaultTimeoutMillis;
    private final Duration defaultStreamIdleTimeout;
    private final String timeoutHeader;
    private final int maxInFlight;
    private final int maxNotifyInFlight;
    private final int maxResponseBytes;
    private final int maxStreamEventChars;
    private final long maxStreamBytes;
    private final Map<String, String> defaultHeaders;
    private final List<DynamicHeader> dynamicHeaders;
    private final List<PropagatedHeader> propagated;
    private final AiHttpPool pool;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger notifyInFlight = new AtomicInteger();
    private final Set<InFlightCall> active = ConcurrentHashMap.newKeySet();
    private final AiClientMetrics metrics;
    private volatile boolean closed;

    private AiClient(Builder b) {
        String scheme = b.baseUri.getScheme();
        String host = b.baseUri.getHost();
        int explicitPort = b.baseUri.getPort();
        int defaultPort = "https".equalsIgnoreCase(scheme) ? 443 : 80;
        int effectivePort = explicitPort > 0 ? explicitPort : defaultPort;
        this.baseUrl = normalizeBaseUrl(b.baseUri);
        this.basePath = normalizeBasePath(b.baseUri.getRawPath());
        this.hostHeader = effectivePort == defaultPort ? host : (host + ":" + effectivePort);
        this.codec = b.codec;
        this.dispatcher = b.dispatcher;
        this.defaultTimeout = b.timeout;
        this.defaultTimeoutMillis = Long.toString(Math.max(1L, b.timeout.toMillis()));
        this.defaultStreamIdleTimeout = b.streamIdleTimeout;
        this.timeoutHeader = b.timeoutHeader;
        this.maxInFlight = b.maxInFlight;
        this.maxNotifyInFlight = b.maxNotifyInFlight;
        this.maxResponseBytes = b.maxResponseBytes;
        this.maxStreamEventChars = b.maxStreamEventChars;
        this.maxStreamBytes = b.maxStreamBytes;
        this.defaultHeaders = Map.copyOf(b.headers);
        this.dynamicHeaders = List.copyOf(b.dynamicHeaders);
        this.propagated = List.copyOf(b.propagated);
        int connectMillis = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, b.connectTimeout.toMillis()));
        this.pool = new AiHttpPool(b.bootstrap, scheme, host, effectivePort, b.ssl, connectMillis);
        this.metrics = new AiClientMetrics(b.metrics, b.clientName, inFlight::get, notifyInFlight::get);
        LOG.log(Level.INFO, () -> "AiClient ready for " + baseUrl + " (maxInFlight=" + maxInFlight
                + ", timeout=" + defaultTimeout + ")");
    }

    public static Builder builder(String baseUrl) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        return builder(URI.create(baseUrl));
    }

    public static Builder builder(URI baseUri) { return new Builder(baseUri); }

    /** Sends an asynchronous POST request and decodes a 2xx response into {@code responseType}. */
    public <T> AiCall call(String path, Object body, Class<T> responseType, AiCallback<T> callback) {
        return call(AiRequest.of(path, body), (Type) responseType, callback);
    }

    /** Sends an asynchronous request and decodes a 2xx response into {@code responseType}. */
    public <T> AiCall call(AiRequest request, Class<T> responseType, AiCallback<T> callback) {
        return call(request, (Type) responseType, callback);
    }

    /** Sends an asynchronous request and decodes a 2xx response into the generic {@code responseType}. */
    public <T> AiCall call(AiRequest request, Type responseType, AiCallback<T> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(callback, "callback");
        Executor routeExecutor = Objects.requireNonNull(dispatcher.capture(request.context()), "captured executor");
        Metadata ambient = dispatcher.metadata(request.context());
        FullHttpRequest httpRequest = buildHttpRequest(request, ambient, "application/json");
        metrics.callStarted();
        if (closed) {
            httpRequest.release();
            return failImmediately(routeExecutor, AI_CLOSED, "AiClient is closed", callback);
        }
        if (!tryAcquireSlot(inFlight, maxInFlight)) {
            httpRequest.release();
            LOG.log(Level.WARNING, () -> "AiClient overloaded (" + maxInFlight + " in flight) for " + request.path());
            return failImmediately(routeExecutor, AI_OVERLOADED, "Max in-flight AI calls reached (" + maxInFlight + ")", callback);
        }
        long startNanos = metrics.startTimer();
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        UnaryCall<T> call = new UnaryCall<>(startNanos, request.path(), responseType, routeExecutor, callback);
        active.add(call);
        if (closed) {
            httpRequest.release();
            call.abort(AI_CLOSED, 0, "AiClient is closed");
            return call;
        }
        if (LOG.isLoggable(Level.DEBUG)) LOG.log(Level.DEBUG, () -> "AI call " + request.method() + " " + httpRequest.uri());
        Timeout totalTimeout = AiTimer.newTimeout(t -> {
            if (call.abort(AI_TIMEOUT, 0, "AI call timed out after " + timeout)) {
                LOG.log(Level.WARNING, () -> "AI call timed out: " + request.path());
            }
        }, timeout.toNanos());
        call.attachTimer(totalTimeout);
        pool.send(httpRequest, call);
        return call;
    }

    /** Sends an asynchronous POST request expecting a {@code text/event-stream} (W3C SSE) response. */
    public AiCall stream(String path, Object body, Consumer<AiStreamEvent> onEvent, AiCallback<Void> onComplete) {
        return stream(AiRequest.of(path, body), onEvent, onComplete);
    }

    /** Sends an asynchronous request expecting a {@code text/event-stream} (W3C SSE) response. */
    public AiCall stream(AiRequest request, Consumer<AiStreamEvent> onEvent, AiCallback<Void> onComplete) {
        Objects.requireNonNull(onEvent, "onEvent");
        Objects.requireNonNull(onComplete, "onComplete");
        return stream(request, new AiStreamListener() {
            @Override public void onEvent(AiStreamEvent event) { onEvent.accept(event); }
            @Override public void onComplete(AiResult<Void> result) { onComplete.onResponse(result); }
        });
    }

    /** Sends an asynchronous POST request expecting a {@code text/event-stream} (W3C SSE) response. */
    public AiCall stream(String path, Object body, AiStreamListener listener) {
        return stream(AiRequest.of(path, body), listener);
    }

    /** Sends an asynchronous request expecting a {@code text/event-stream} (W3C SSE) response. */
    public AiCall stream(AiRequest request, AiStreamListener listener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        return startStream(request, null, listener::onEvent, listener::onComplete);
    }

    /**
     * Sends an asynchronous POST request expecting an SSE stream of JSON payloads decoded off-route into
     * {@code eventType} (automatically skipping {@code [DONE]} sentinel frames).
     */
    public <T> AiCall stream(String path, Object body, Class<T> eventType,
            Consumer<T> onEvent, AiCallback<Void> onComplete) {
        return stream(AiRequest.of(path, body), (Type) eventType, onEvent, onComplete);
    }

    /**
     * Sends an asynchronous request expecting an SSE stream of JSON payloads decoded off-route into {@code eventType}
     * (automatically skipping {@code [DONE]} sentinel frames).
     */
    public <T> AiCall stream(AiRequest request, Class<T> eventType,
            Consumer<T> onEvent, AiCallback<Void> onComplete) {
        return stream(request, (Type) eventType, onEvent, onComplete);
    }

    /**
     * Sends an asynchronous request expecting an SSE stream of JSON payloads decoded off-route into the generic
     * {@code eventType} (automatically skipping {@code [DONE]} sentinel frames).
     */
    @SuppressWarnings("unchecked")
    public <T> AiCall stream(AiRequest request, Type eventType,
            Consumer<T> onEvent, AiCallback<Void> onComplete) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(onEvent, "onEvent");
        Objects.requireNonNull(onComplete, "onComplete");
        return startStream(request, eventType, (Consumer<Object>) onEvent, onComplete::onResponse);
    }

    private <E> AiCall startStream(AiRequest request, Type decodedEventType,
            Consumer<E> onItem, Consumer<AiResult<Void>> onComplete) {
        Executor routeExecutor = Objects.requireNonNull(dispatcher.capture(request.context()), "captured executor");
        Metadata ambient = dispatcher.metadata(request.context());
        FullHttpRequest httpRequest = buildHttpRequest(request, ambient, "text/event-stream");
        metrics.streamStarted();
        if (closed) {
            httpRequest.release();
            return failStreamImmediately(routeExecutor, AI_CLOSED, "AiClient is closed", onComplete);
        }
        if (!tryAcquireSlot(inFlight, maxInFlight)) {
            httpRequest.release();
            LOG.log(Level.WARNING, () -> "AiClient overloaded (" + maxInFlight + " in flight) for stream " + request.path());
            return failStreamImmediately(routeExecutor, AI_OVERLOADED, "Max in-flight AI calls reached (" + maxInFlight + ")", onComplete);
        }
        long startNanos = metrics.startTimer();
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        Duration idleTimeout = request.idleTimeout() != null ? request.idleTimeout() : defaultStreamIdleTimeout;
        StreamCall<E> call = new StreamCall<>(startNanos, routeExecutor, request.path(), decodedEventType, onItem, onComplete);
        active.add(call);
        if (closed) {
            httpRequest.release();
            call.abort(AI_CLOSED, 0, "AiClient is closed");
            return call;
        }
        if (LOG.isLoggable(Level.DEBUG)) LOG.log(Level.DEBUG, () -> "AI stream " + request.method() + " " + httpRequest.uri());
        Timeout totalTimeout = AiTimer.newTimeout(t -> {
            if (call.abort(AI_TIMEOUT, 0, "AI stream timed out after " + timeout)) {
                LOG.log(Level.WARNING, () -> "AI stream timed out: " + request.path());
            }
        }, timeout.toNanos());
        call.attachTimer(totalTimeout);
        if (idleTimeout != null) {
            call.startIdleTimer(idleTimeout.toNanos(), idleTimeout);
        }
        pool.send(httpRequest, call);
        return call;
    }

    /**
     * Sends a one-way asynchronous POST notification (such as a game event) without waiting for a response body.
     * Uses its own {@code maxNotifyInFlight} budget and returns {@link WriteStatus#ACCEPTED},
     * {@link WriteStatus#INACTIVE} when closed, or {@link WriteStatus#NOT_WRITABLE} when {@code maxNotifyInFlight} is
     * full; throws {@link IllegalArgumentException} if {@code body} cannot be encoded.
     */
    public WriteStatus notify(String path, Object body) {
        return notify(AiRequest.of(path, body));
    }

    /**
     * Sends a one-way asynchronous notification without waiting for a response body. Automatically propagates ambient
     * {@link AiDispatcher#metadata} headers. Returns {@link WriteStatus#ACCEPTED}, {@link WriteStatus#INACTIVE} when
     * closed, or {@link WriteStatus#NOT_WRITABLE} when {@code maxNotifyInFlight} is full; throws
     * {@link IllegalArgumentException} if the request cannot be built or encoded.
     */
    public WriteStatus notify(AiRequest request) {
        Objects.requireNonNull(request, "request");
        Metadata ambient = dispatcher.metadata(request.context());
        FullHttpRequest httpRequest = buildHttpRequest(request, ambient, "application/json");
        metrics.notifyStarted();
        if (closed) {
            httpRequest.release();
            metrics.failed(AI_CLOSED);
            LOG.log(Level.WARNING, () -> "AI notify refused (closed): " + request.path());
            return WriteStatus.INACTIVE;
        }
        if (!tryAcquireSlot(notifyInFlight, maxNotifyInFlight)) {
            httpRequest.release();
            metrics.failed(AI_OVERLOADED);
            LOG.log(Level.WARNING, () -> "AI notify refused (overloaded): " + request.path());
            return WriteStatus.NOT_WRITABLE;
        }
        long startNanos = metrics.startTimer();
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        NotifyCall call = new NotifyCall(startNanos, request.path());
        active.add(call);
        if (closed) {
            httpRequest.release();
            call.abort(AI_CLOSED, 0, "AiClient is closed");
            return WriteStatus.INACTIVE;
        }
        if (LOG.isLoggable(Level.DEBUG)) LOG.log(Level.DEBUG, () -> "AI notify " + request.method() + " " + httpRequest.uri());
        Timeout timerHandle = AiTimer.newTimeout(t -> {
            if (call.abort(AI_TIMEOUT, 0, "AI notify timed out after " + timeout)) {
                LOG.log(Level.WARNING, () -> "AI notify timed out: " + request.path());
            }
        }, timeout.toNanos());
        call.attachTimer(timerHandle);
        pool.send(httpRequest, call);
        return WriteStatus.ACCEPTED;
    }

    /** Current number of in-flight {@link #call} and {@link #stream} requests. */
    public int inFlight() { return inFlight.get(); }

    /** Current number of in-flight one-way {@link #notify} requests. */
    public int notifyInFlight() { return notifyInFlight.get(); }

    public boolean isClosed() { return closed; }

    /**
     * Closes the client: subsequent calls fail with {@link cn.managame.FrameworkErrorCodes#AI_CLOSED}, in-flight calls,
     * streams, and notifications are aborted with {@code AI_CLOSED} (cancelling their timers and closing active
     * channels), pooled connections and any owned EventLoopGroup are shut down, and owned meters are closed. Safe to
     * call more than once.
     */
    @Override public void close() {
        if (closed) return;
        closed = true;
        LOG.log(Level.INFO, () -> "Closing AiClient for " + baseUrl);
        for (InFlightCall call : active) {
            call.abort(AI_CLOSED, 0, "AiClient closed");
        }
        pool.close();
        metrics.close();
    }

    private FullHttpRequest buildHttpRequest(AiRequest request, Metadata ambientMetadata, String accept) {
        ByteBuf bodyBuf = null;
        try {
            String target = resolveRequestTarget(request.path());
            Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
            Object body = request.body();
            if (body == null) {
                bodyBuf = Unpooled.EMPTY_BUFFER;
            } else if (body instanceof String s) {
                bodyBuf = s.isEmpty() ? Unpooled.EMPTY_BUFFER : ByteBufUtil.writeUtf8(ByteBufAllocator.DEFAULT, s);
            } else if (body instanceof byte[] bytes) {
                bodyBuf = bytes.length == 0 ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(bytes);
            } else {
                byte[] encoded = codec.encodeBytes(body);
                bodyBuf = encoded.length == 0 ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(encoded);
            }
            HttpMethod method = HttpMethod.valueOf(request.method());
            DefaultFullHttpRequest nettyReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, target, bodyBuf);
            HttpHeaders headers = nettyReq.headers();
            headers.set(HttpHeaderNames.HOST, hostHeader);
            headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            headers.set(HttpHeaderNames.ACCEPT, accept);
            if (body != null) {
                headers.set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
                headers.setInt(HttpHeaderNames.CONTENT_LENGTH, bodyBuf.readableBytes());
            } else if (HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)) {
                headers.setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            }
            if (!timeoutHeader.isEmpty()) {
                String millis = request.timeout() == null
                        ? defaultTimeoutMillis
                        : Long.toString(Math.max(1L, timeout.toMillis()));
                headers.set(timeoutHeader, millis);
            }
            for (Map.Entry<String, String> entry : defaultHeaders.entrySet()) {
                headers.set(entry.getKey(), entry.getValue());
            }
            for (DynamicHeader dynamic : dynamicHeaders) {
                String value = dynamic.supplier().get();
                if (value != null) headers.set(dynamic.name(), value);
            }
            if (ambientMetadata != null) propagateHeaders(headers, ambientMetadata);
            propagateHeaders(headers, request.metadata());
            for (Map.Entry<String, String> entry : request.headers().entrySet()) {
                headers.set(entry.getKey(), entry.getValue());
            }
            return nettyReq;
        } catch (Throwable cause) {
            if (bodyBuf != null) bodyBuf.release();
            if (cause instanceof IllegalArgumentException badArg) throw badArg;
            throw new IllegalArgumentException(
                    "Failed to build AI request for " + request.path() + ": " + describeError(cause), cause);
        }
    }

    private void propagateHeaders(HttpHeaders headers, Metadata metadata) {
        if (metadata.isEmpty() || propagated.isEmpty()) return;
        for (PropagatedHeader entry : propagated) {
            if (metadata.contains(entry.key())) {
                Object value = metadata.get(entry.key());
                if (value != null) headers.set(entry.header(), value.toString());
            }
        }
    }

    private Object decodeBody(byte[] body, Type responseType) throws Exception {
        if (responseType == Void.class) return null;
        if (responseType == byte[].class) return body != null ? body : EMPTY_BYTES;
        if (responseType == String.class) {
            return body == null || body.length == 0 ? "" : new String(body, StandardCharsets.UTF_8);
        }
        return codec.decodeBytes(body != null ? body : EMPTY_BYTES, responseType);
    }

    private Object decodeStreamPayload(String payload, Type eventType) throws Exception {
        if (eventType == String.class) return payload;
        if (eventType == byte[].class) return payload.getBytes(StandardCharsets.UTF_8);
        return codec.decode(payload, eventType);
    }

    private String resolveRequestTarget(String path) {
        validateRequestPath(path);
        if (path.isEmpty()) return basePath.isEmpty() ? "/" : basePath;
        if (path.startsWith("?")) return (basePath.isEmpty() ? "/" : basePath) + path;
        if (path.startsWith("/")) return basePath.isEmpty() ? path : basePath + path;
        return (basePath.isEmpty() ? "/" : basePath + "/") + path;
    }

    private static void validateRequestPath(String path) {
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c >= 0x7F || c == '#' || c == '"' || c == '<' || c == '>'
                    || c == '\\' || c == '^' || c == '`' || c == '{' || c == '|' || c == '}') {
                throw new IllegalArgumentException("Invalid URI character in request path: " + path);
            }
        }
    }

    private static String normalizeBaseUrl(URI uri) {
        String raw = uri.toString();
        while (raw.endsWith("/") && raw.length() > uri.getScheme().length() + 3) {
            raw = raw.substring(0, raw.length() - 1);
        }
        return raw;
    }

    private static String normalizeBasePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) return "";
        String p = rawPath;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return "/".equals(p) ? "" : p;
    }

    private static boolean tryAcquireSlot(AtomicInteger counter, int limit) {
        while (true) {
            int current = counter.get();
            if (current >= limit) return false;
            if (counter.compareAndSet(current, current + 1)) return true;
        }
    }

    private static void dispatchCallback(Executor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (RuntimeException rejected) {
            LOG.log(Level.WARNING, () -> "AiClient callback dispatcher rejected delivery: " + describeError(rejected));
        }
    }

    private <T> AiCall failImmediately(Executor executor, int code, String message, AiCallback<T> callback) {
        metrics.failed(code);
        AiResult<T> result = AiResult.fail(code, message);
        dispatchCallback(executor, () -> deliver(callback, result));
        return CompletedCall.INSTANCE;
    }

    private AiCall failStreamImmediately(Executor executor, int code, String message,
            Consumer<AiResult<Void>> onComplete) {
        metrics.failed(code);
        AiResult<Void> result = AiResult.fail(code, message);
        dispatchCallback(executor, () -> deliverComplete(onComplete, result));
        return CompletedCall.INSTANCE;
    }

    private static <T> void deliver(AiCallback<T> callback, AiResult<T> result) {
        try {
            callback.onResponse(result);
        } catch (Throwable cause) {
            LOG.log(Level.ERROR, "Uncaught exception in AiCallback", cause);
        }
    }

    private static void deliverComplete(Consumer<AiResult<Void>> onComplete, AiResult<Void> result) {
        try {
            onComplete.accept(result);
        } catch (Throwable cause) {
            LOG.log(Level.ERROR, "Uncaught exception in stream completion handler", cause);
        }
    }

    private static boolean is2xx(int status) { return status >= 200 && status < 300; }

    private static int mapStatus(int status) {
        if (status == 429 || status == 503) return AI_OVERLOADED;
        if (status == 408 || status == 504) return AI_TIMEOUT;
        if (status >= 400 && status < 500) return AI_PROTOCOL_ERROR;
        return AI_UNAVAILABLE;
    }

    private static int mapThrowable(Throwable error) {
        Throwable root = unwrap(error);
        if (root instanceof ConnectTimeoutException) return AI_TIMEOUT;
        if (root instanceof CancellationException) return AI_CANCELLED;
        if (root instanceof IOException) return AI_UNAVAILABLE;
        return AI_UNAVAILABLE;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String describeError(Throwable error) {
        String msg = error.getMessage();
        return msg != null && !msg.isBlank() ? msg : error.getClass().getSimpleName();
    }

    private static String truncateError(byte[] body, int status) {
        if (body == null || body.length == 0) return "HTTP " + status;
        int len = Math.min(body.length, MAX_ERROR_BODY_CHARS * 4);
        String text = new String(body, 0, len, StandardCharsets.UTF_8);
        if (text.isBlank()) return "HTTP " + status;
        return text.length() <= MAX_ERROR_BODY_CHARS ? text : text.substring(0, MAX_ERROR_BODY_CHARS);
    }

    private record DynamicHeader(String name, Supplier<String> supplier) {}

    private record PropagatedHeader(MetadataKey<?> key, String header) {}

    private enum CompletedCall implements AiCall {
        INSTANCE;
        @Override public boolean cancel() { return false; }
        @Override public boolean isDone() { return true; }
    }

    private abstract class InFlightCall implements AiCall, AiHttpPool.ExchangeListener {
        private final long startNanos;
        private final AtomicBoolean done = new AtomicBoolean();
        private final AtomicReference<ChannelFuture> connectFuture = new AtomicReference<>();
        private final AtomicReference<Channel> assignedChannel = new AtomicReference<>();
        private final AtomicReference<Timeout> totalTimerRef = new AtomicReference<>();

        InFlightCall(long startNanos) { this.startNanos = startNanos; }

        final long startNanos() { return startNanos; }

        final Channel channel() { return assignedChannel.get(); }

        @Override public void onConnectFuture(ChannelFuture future) {
            connectFuture.set(future);
            if (done.get()) future.cancel(false);
        }

        @Override public boolean onChannelAssigned(Channel channel) {
            if (done.get()) return false;
            assignedChannel.set(channel);
            if (done.get()) {
                channel.close();
                return false;
            }
            return true;
        }

        void attachTimer(Timeout timeout) {
            totalTimerRef.set(timeout);
            if (done.get()) timeout.cancel();
        }

        boolean markDone() {
            if (!done.compareAndSet(false, true)) return false;
            active.remove(this);
            releaseSlotAndRecordLatency();
            Timeout total = totalTimerRef.getAndSet(null);
            if (total != null) total.cancel();
            return true;
        }

        abstract void releaseSlotAndRecordLatency();

        void cancelTransport() {
            ChannelFuture cf = connectFuture.get();
            if (cf != null) cf.cancel(false);
            Channel ch = assignedChannel.get();
            if (ch != null) ch.close();
        }

        abstract boolean abort(int code, int status, String message);

        @Override public boolean isDone() { return done.get(); }

        @Override public boolean cancel() { return abort(AI_CANCELLED, 0, "Cancelled"); }
    }

    private final class UnaryCall<T> extends InFlightCall {
        private final String path;
        private final Type responseType;
        private final Executor routeExecutor;
        private final AiCallback<T> callback;
        private int status;
        private byte[] firstChunk;
        private ByteArrayOutputStream multiChunk;
        private int totalBytes;

        UnaryCall(long startNanos, String path, Type responseType, Executor routeExecutor, AiCallback<T> callback) {
            super(startNanos);
            this.path = path;
            this.responseType = responseType;
            this.routeExecutor = routeExecutor;
            this.callback = callback;
        }

        @Override void releaseSlotAndRecordLatency() {
            inFlight.decrementAndGet();
            metrics.recordCallLatency(startNanos());
        }

        @Override public boolean onResponseHeaders(int status, HttpHeaders headers, Channel channel) {
            if (isDone()) return false;
            this.status = status;
            String cl = headers.get(HttpHeaderNames.CONTENT_LENGTH);
            if (cl != null) {
                try {
                    long declared = Long.parseLong(cl.trim());
                    if (declared > maxResponseBytes) {
                        abort(AI_PROTOCOL_ERROR, status,
                                "AI response Content-Length (" + declared + ") exceeded maxResponseBytes (" + maxResponseBytes + ")");
                        return false;
                    }
                } catch (NumberFormatException ignored) {}
            }
            return true;
        }

        @Override public boolean onContent(ByteBuf content, boolean last, Channel channel) {
            if (isDone()) return false;
            int readable = content.readableBytes();
            if (readable == 0) return true;
            if (readable > maxResponseBytes - totalBytes) {
                abort(AI_PROTOCOL_ERROR, status, "AI response body exceeded maxResponseBytes (" + maxResponseBytes + ")");
                return false;
            }
            totalBytes += readable;
            if (firstChunk == null && multiChunk == null) {
                byte[] chunk = new byte[readable];
                content.getBytes(content.readerIndex(), chunk);
                firstChunk = chunk;
            } else {
                if (multiChunk == null) {
                    multiChunk = new ByteArrayOutputStream(Math.max(256, firstChunk.length + readable));
                    multiChunk.write(firstChunk, 0, firstChunk.length);
                    firstChunk = null;
                }
                if (content.hasArray()) {
                    multiChunk.write(content.array(), content.arrayOffset() + content.readerIndex(), readable);
                } else {
                    byte[] tmp = new byte[readable];
                    content.getBytes(content.readerIndex(), tmp);
                    multiChunk.write(tmp, 0, readable);
                }
            }
            return true;
        }

        @Override public void onComplete() {
            if (isDone()) return;
            byte[] body = totalBytes == 0 ? EMPTY_BYTES
                    : (firstChunk != null ? firstChunk : multiChunk.toByteArray());
            firstChunk = null;
            multiChunk = null;
            if (!is2xx(status)) {
                int code = mapStatus(status);
                if (code == AI_OVERLOADED || code == AI_TIMEOUT) {
                    LOG.log(Level.WARNING, () -> "AI call " + path + " failed with HTTP " + status);
                }
                finish(AiResult.fail(code, status, truncateError(body, status)));
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                T decoded = (T) decodeBody(body, responseType);
                finish(AiResult.ok(status, decoded));
            } catch (Throwable cause) {
                LOG.log(Level.ERROR, "Failed to decode AI response for " + path, cause);
                finish(AiResult.fail(AI_PROTOCOL_ERROR, status, describeError(cause)));
            }
        }

        @Override public void onFailure(Throwable cause) {
            if (isDone()) return;
            int code = mapThrowable(cause);
            if (code == AI_TIMEOUT) LOG.log(Level.WARNING, () -> "AI call timed out: " + path);
            finish(AiResult.fail(code, describeError(unwrap(cause))));
        }

        private void finish(AiResult<T> result) {
            if (!markDone()) return;
            if (!result.isSuccess()) metrics.failed(result.code());
            dispatchCallback(routeExecutor, () -> deliver(callback, result));
        }

        @Override boolean abort(int code, int status, String message) {
            if (!markDone()) return false;
            cancelTransport();
            metrics.failed(code);
            AiResult<T> result = AiResult.fail(code, status, message);
            dispatchCallback(routeExecutor, () -> deliver(callback, result));
            return true;
        }
    }

    private final class StreamCall<E> extends InFlightCall {
        private final Executor routeExecutor;
        private final String path;
        private final Type decodedEventType;
        private final Consumer<E> onItem;
        private final Consumer<AiResult<Void>> onComplete;
        private final ConcurrentLinkedQueue<E> eventQueue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger queuedCount = new AtomicInteger();
        private final AtomicInteger wip = new AtomicInteger();
        private final AtomicBoolean completedDelivered = new AtomicBoolean();
        private final AtomicReference<Timeout> idleTimerRef = new AtomicReference<>();
        private SseParser parser;
        private ByteArrayOutputStream errorBody;
        private int errorBytes;
        private int status;
        private boolean non2xx;
        private long streamBytes;
        private volatile boolean readPaused;
        private volatile boolean aborted;
        private volatile AiResult<Void> terminalResult;
        private volatile long lastActivityNanos;

        StreamCall(long startNanos, Executor routeExecutor, String path, Type decodedEventType,
                Consumer<E> onItem, Consumer<AiResult<Void>> onComplete) {
            super(startNanos);
            this.routeExecutor = routeExecutor;
            this.path = path;
            this.decodedEventType = decodedEventType;
            this.onItem = onItem;
            this.onComplete = onComplete;
        }

        @Override void releaseSlotAndRecordLatency() {
            inFlight.decrementAndGet();
            metrics.recordStreamLatency(startNanos());
        }

        void startIdleTimer(long idleNanos, Duration idleDuration) {
            lastActivityNanos = System.nanoTime();
            scheduleIdleCheck(idleNanos, idleNanos, idleDuration);
        }

        private void scheduleIdleCheck(long delayNanos, long idleNanos, Duration idleDuration) {
            if (isDone() || closed) return;
            Timeout next = AiTimer.newTimeout(t -> {
                if (isDone()) return;
                long remaining = idleNanos - (System.nanoTime() - lastActivityNanos);
                if (remaining <= 0) {
                    if (abort(AI_TIMEOUT, 0, "AI stream idle timed out after " + idleDuration)) {
                        LOG.log(Level.WARNING, () -> "AI stream idle timed out: " + path);
                    }
                } else {
                    scheduleIdleCheck(remaining, idleNanos, idleDuration);
                }
            }, delayNanos);
            Timeout previous = idleTimerRef.getAndSet(next);
            if (previous != null) previous.cancel();
            if (isDone()) next.cancel();
        }

        @Override public boolean onResponseHeaders(int status, HttpHeaders headers, Channel channel) {
            if (isDone()) return false;
            this.status = status;
            this.lastActivityNanos = System.nanoTime();
            if (!is2xx(status)) {
                this.non2xx = true;
                this.errorBody = new ByteArrayOutputStream(128);
            } else {
                this.parser = new SseParser(this::handleParsedEvent, maxStreamEventChars);
            }
            return true;
        }

        @Override public boolean onContent(ByteBuf content, boolean last, Channel channel) {
            if (isDone()) return false;
            int readable = content.readableBytes();
            if (readable == 0) return true;
            lastActivityNanos = System.nanoTime();
            if (non2xx) {
                int limit = Math.min(maxResponseBytes, 4096);
                int toCopy = Math.min(readable, limit - errorBytes);
                if (toCopy > 0) {
                    byte[] tmp = new byte[toCopy];
                    content.getBytes(content.readerIndex(), tmp);
                    errorBody.write(tmp, 0, toCopy);
                    errorBytes += toCopy;
                }
                return true;
            }
            streamBytes += readable;
            if (streamBytes > maxStreamBytes) {
                LOG.log(Level.WARNING, () -> "AI SSE stream exceeded maxStreamBytes (" + maxStreamBytes + "): " + path);
                abort(AI_PROTOCOL_ERROR, status, "AI SSE stream exceeded maxStreamBytes (" + maxStreamBytes + ")");
                return false;
            }
            try {
                if (content.nioBufferCount() == 1) {
                    parser.feed(content.internalNioBuffer(content.readerIndex(), readable));
                } else {
                    for (ByteBuffer buf : content.nioBuffers()) {
                        if (isDone()) return false;
                        parser.feed(buf);
                    }
                }
            } catch (IllegalArgumentException badSse) {
                LOG.log(Level.WARNING, () -> "AI SSE stream aborted: " + badSse.getMessage());
                abort(AI_PROTOCOL_ERROR, status, badSse.getMessage());
                return false;
            }
            return !isDone();
        }

        @Override public void onComplete() {
            if (isDone()) return;
            if (non2xx) {
                byte[] err = errorBody != null ? errorBody.toByteArray() : EMPTY_BYTES;
                errorBody = null;
                finish(AiResult.fail(mapStatus(status), status, truncateError(err, status)));
                return;
            }
            try {
                if (parser != null) parser.finish();
                if (!isDone()) finish(AiResult.ok(status, null));
            } catch (IllegalArgumentException badSse) {
                finish(AiResult.fail(AI_PROTOCOL_ERROR, status, badSse.getMessage()));
            }
        }

        @Override public void onFailure(Throwable cause) {
            if (isDone()) return;
            int code = mapThrowable(cause);
            if (code == AI_TIMEOUT) LOG.log(Level.WARNING, () -> "AI stream timed out: " + path);
            finish(AiResult.fail(code, describeError(unwrap(cause))));
        }

        @SuppressWarnings("unchecked")
        private void handleParsedEvent(AiStreamEvent event) {
            if (isDone()) return;
            if ("error".equals(event.event())) {
                String errMsg = event.data().isBlank() ? "SSE error event" : event.data();
                LOG.log(Level.WARNING, () -> "AI stream received error event on " + path + ": " + errMsg);
                finishAndCancel(AiResult.fail(AI_UNAVAILABLE, status, errMsg));
                return;
            }
            E item;
            if (decodedEventType == null) {
                item = (E) event;
            } else {
                if (isDoneSentinel(event)) return;
                try {
                    item = (E) decodeStreamPayload(event.data(), decodedEventType);
                } catch (Throwable decodeErr) {
                    LOG.log(Level.ERROR, "Failed to decode SSE event for " + path, decodeErr);
                    abort(AI_PROTOCOL_ERROR, status, describeError(decodeErr));
                    return;
                }
            }
            eventQueue.offer(item);
            if (queuedCount.incrementAndGet() >= STREAM_HIGH_WATER && !readPaused) {
                readPaused = true;
                Channel ch = channel();
                if (ch != null) ch.config().setAutoRead(false);
            }
            scheduleDrain();
        }

        private static boolean isDoneSentinel(AiStreamEvent event) {
            String d = event.data().trim();
            return "[DONE]".equals(d) || ("done".equalsIgnoreCase(event.event()) && d.isEmpty());
        }

        private void finish(AiResult<Void> result) {
            if (!markDone()) return;
            cancelIdleTimer();
            if (!result.isSuccess()) metrics.failed(result.code());
            terminalResult = result;
            scheduleDrain();
        }

        private void finishAndCancel(AiResult<Void> result) {
            if (!markDone()) return;
            cancelIdleTimer();
            cancelTransport();
            if (!result.isSuccess()) metrics.failed(result.code());
            terminalResult = result;
            scheduleDrain();
        }

        @Override boolean abort(int code, int status, String message) {
            if (!markDone()) return false;
            aborted = true;
            cancelIdleTimer();
            cancelTransport();
            metrics.failed(code);
            eventQueue.clear();
            queuedCount.set(0);
            terminalResult = AiResult.fail(code, status, message);
            scheduleDrain();
            return true;
        }

        private void scheduleDrain() {
            if (wip.getAndIncrement() != 0) return;
            try {
                routeExecutor.execute(this::drainLoop);
            } catch (RuntimeException rejected) {
                aborted = true;
                eventQueue.clear();
                queuedCount.set(0);
                cancelIdleTimer();
                cancelTransport();
                if (markDone()) metrics.failed(AI_UNAVAILABLE);
                LOG.log(Level.WARNING, () -> "AiClient callback dispatcher rejected stream delivery for "
                        + path + ": " + describeError(rejected));
            }
        }

        private void drainLoop() {
            int missed = 1;
            while (true) {
                if (completedDelivered.get()) {
                    eventQueue.clear();
                    return;
                }
                while (!aborted) {
                    E ev = eventQueue.poll();
                    if (ev == null) break;
                    int rem = queuedCount.decrementAndGet();
                    if (rem <= STREAM_LOW_WATER && readPaused && !isDone()) {
                        resumeAutoRead();
                    }
                    metrics.streamEvent();
                    try {
                        onItem.accept(ev);
                    } catch (Throwable cause) {
                        LOG.log(Level.ERROR, "Uncaught exception in stream event listener", cause);
                    }
                }
                if (aborted) {
                    eventQueue.clear();
                    queuedCount.set(0);
                }
                AiResult<Void> done = terminalResult;
                if (done != null && (aborted || eventQueue.isEmpty())) {
                    if (completedDelivered.compareAndSet(false, true)) {
                        deliverComplete(onComplete, done);
                    }
                    return;
                }
                missed = wip.addAndGet(-missed);
                if (missed == 0) return;
            }
        }

        private void resumeAutoRead() {
            Channel ch = channel();
            if (ch == null || !ch.isActive()) return;
            ch.eventLoop().execute(() -> {
                if (!isDone() && readPaused && queuedCount.get() <= STREAM_LOW_WATER) {
                    readPaused = false;
                    ch.config().setAutoRead(true);
                }
            });
        }

        private void cancelIdleTimer() {
            Timeout idle = idleTimerRef.getAndSet(null);
            if (idle != null) idle.cancel();
        }
    }

    private final class NotifyCall extends InFlightCall {
        private final String path;
        private int status;

        NotifyCall(long startNanos, String path) {
            super(startNanos);
            this.path = path;
        }

        @Override void releaseSlotAndRecordLatency() {
            notifyInFlight.decrementAndGet();
            metrics.recordNotifyLatency(startNanos());
        }

        @Override public boolean onResponseHeaders(int status, HttpHeaders headers, Channel channel) {
            this.status = status;
            return !isDone();
        }

        @Override public boolean onContent(ByteBuf content, boolean last, Channel channel) {
            return !isDone();
        }

        @Override public void onComplete() {
            if (!markDone()) return;
            if (!is2xx(status)) {
                int code = mapStatus(status);
                metrics.failed(code);
                LOG.log(Level.WARNING, () -> "AI notify failed for " + path + " with HTTP " + status);
            }
        }

        @Override public void onFailure(Throwable cause) {
            if (!markDone()) return;
            int code = mapThrowable(cause);
            metrics.failed(code);
            LOG.log(Level.WARNING, () -> "AI notify failed for " + path + ": " + describeError(unwrap(cause)));
        }

        @Override boolean abort(int code, int status, String message) {
            if (!markDone()) return false;
            cancelTransport();
            metrics.failed(code);
            return true;
        }
    }

    public static final class Builder {
        private final URI baseUri;
        private AiCodec codec = AiCodec.basic();
        private AiDispatcher dispatcher = AiDispatcher.virtualThreads();
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration streamIdleTimeout;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private String timeoutHeader = TIMEOUT_HEADER;
        private int maxInFlight = DEFAULT_MAX_IN_FLIGHT;
        private int maxNotifyInFlight = DEFAULT_MAX_IN_FLIGHT;
        private int maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
        private int maxStreamEventChars = SseParser.DEFAULT_MAX_EVENT_CHARS;
        private long maxStreamBytes = DEFAULT_MAX_STREAM_BYTES;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final List<DynamicHeader> dynamicHeaders = new ArrayList<>();
        private final List<PropagatedHeader> propagated = new ArrayList<>();
        private Bootstrap bootstrap;
        private SslContext ssl;
        private Metrics metrics = Metrics.noop();
        private String clientName = "";

        private Builder(URI baseUri) {
            this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
            String scheme = baseUri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("baseUri must use http or https: " + baseUri);
            }
            if (baseUri.getHost() == null || baseUri.getHost().isBlank()) {
                throw new IllegalArgumentException("baseUri must have a host: " + baseUri);
            }
        }

        /** Codec for encoding DTO requests and decoding typed responses; {@link AiCodec#basic()} by default. */
        public Builder codec(AiCodec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * Context and route bridge capturing the caller's execution continuation and ambient {@link Metadata} on every
         * call; {@link AiDispatcher#virtualThreads()} by default.
         */
        public Builder dispatcher(AiDispatcher dispatcher) {
            this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
            return this;
        }

        /** Convenience shorthand for {@code dispatcher(AiDispatcher.ofExecutor(executor))}. */
        public Builder executor(Executor executor) {
            return dispatcher(AiDispatcher.ofExecutor(executor));
        }

        /** Default end-to-end timeout per call, stream, or notification; 30 seconds by default. */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
            this.timeout = timeout;
            return this;
        }

        /**
         * Header name used to propagate the request's timeout budget in milliseconds to the AI service
         * ({@link #TIMEOUT_HEADER} by default). Pass {@code null} or {@code ""} to disable sending a timeout header.
         */
        public Builder timeoutHeader(String headerName) {
            this.timeoutHeader = headerName == null ? "" : headerName.trim();
            return this;
        }

        /**
         * Optional idle timeout for SSE streams: fails with {@link cn.managame.FrameworkErrorCodes#AI_TIMEOUT} if no
         * bytes arrive within this duration (both before the first chunk and between chunks). Disabled by default.
         */
        public Builder streamIdleTimeout(Duration idleTimeout) {
            Objects.requireNonNull(idleTimeout, "idleTimeout");
            if (idleTimeout.isNegative() || idleTimeout.isZero()) {
                throw new IllegalArgumentException("streamIdleTimeout must be positive");
            }
            this.streamIdleTimeout = idleTimeout;
            return this;
        }

        /** TCP connect timeout unless overridden on {@link #bootstrap}; 5 seconds by default. */
        public Builder connectTimeout(Duration connectTimeout) {
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            if (connectTimeout.isNegative() || connectTimeout.isZero())
                throw new IllegalArgumentException("connectTimeout must be positive");
            this.connectTimeout = connectTimeout;
            return this;
        }

        /** Maximum concurrent in-flight {@link #call} and {@link #stream} requests before AI_OVERLOADED; 1024 by default. */
        public Builder maxInFlight(int maxInFlight) {
            if (maxInFlight <= 0) throw new IllegalArgumentException("maxInFlight must be positive");
            this.maxInFlight = maxInFlight;
            return this;
        }

        /**
         * Maximum concurrent in-flight one-way {@link #notify} requests before returning
         * {@link WriteStatus#NOT_WRITABLE}, isolated from {@link #maxInFlight}; 1024 by default.
         */
        public Builder maxNotifyInFlight(int maxNotifyInFlight) {
            if (maxNotifyInFlight <= 0) throw new IllegalArgumentException("maxNotifyInFlight must be positive");
            this.maxNotifyInFlight = maxNotifyInFlight;
            return this;
        }

        /** Maximum response body size in bytes for unary calls before failing with AI_PROTOCOL_ERROR; 1 MiB by default. */
        public Builder maxResponseBytes(int maxResponseBytes) {
            if (maxResponseBytes <= 0) throw new IllegalArgumentException("maxResponseBytes must be positive");
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        /** Maximum single SSE event payload size in chars before aborting with AI_PROTOCOL_ERROR; 256 KiB by default. */
        public Builder maxStreamEventChars(int maxStreamEventChars) {
            if (maxStreamEventChars <= 0) throw new IllegalArgumentException("maxStreamEventChars must be positive");
            this.maxStreamEventChars = maxStreamEventChars;
            return this;
        }

        /** Maximum total bytes read on a single SSE stream before aborting with AI_PROTOCOL_ERROR; 16 MiB by default. */
        public Builder maxStreamBytes(long maxStreamBytes) {
            if (maxStreamBytes <= 0) throw new IllegalArgumentException("maxStreamBytes must be positive");
            this.maxStreamBytes = maxStreamBytes;
            return this;
        }

        /** Default static HTTP header sent on every request (for example {@code Authorization}). */
        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (name.isBlank()) throw new IllegalArgumentException("Header name must not be blank");
            this.headers.put(name, value);
            return this;
        }

        /**
         * Dynamic HTTP header evaluated on each request (for example rotatable Bearer or OAuth tokens). Returning
         * {@code null} from {@code valueSupplier} omits the header for that request.
         */
        public Builder header(String name, Supplier<String> valueSupplier) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(valueSupplier, "valueSupplier");
            if (name.isBlank()) throw new IllegalArgumentException("Header name must not be blank");
            this.dynamicHeaders.add(new DynamicHeader(name, valueSupplier));
            return this;
        }

        /**
         * Propagates {@code key} from ambient {@link AiDispatcher#metadata} and {@link AiRequest#metadata()} as HTTP
         * header {@code headerName} when present.
         */
        public Builder propagate(MetadataKey<?> key, String headerName) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(headerName, "headerName");
            if (headerName.isBlank()) throw new IllegalArgumentException("headerName must not be blank");
            this.propagated.add(new PropagatedHeader(key, headerName));
            return this;
        }

        /**
         * Netty {@link Bootstrap} supplying EventLoops, channel factory, and socket options (must leave the handler
         * unset). An EventLoopGroup supplied here belongs to the caller and is not shut down when this client closes.
         */
        public Builder bootstrap(Bootstrap bootstrap) {
            Objects.requireNonNull(bootstrap, "bootstrap");
            if (bootstrap.config().handler() != null) {
                throw new IllegalArgumentException("AiClient sets the bootstrap's handler");
            }
            this.bootstrap = bootstrap;
            return this;
        }

        /** Custom client {@link SslContext} for {@code https} endpoints (for example internal CAs). */
        public Builder ssl(SslContext ssl) {
            Objects.requireNonNull(ssl, "ssl");
            if (!ssl.isClient()) throw new IllegalArgumentException("Client SslContext required");
            this.ssl = ssl;
            return this;
        }

        /** Where metrics are reported; {@link Metrics#noop()} by default. */
        public Builder metrics(Metrics metrics) {
            return metrics(metrics, "");
        }

        /** Where metrics are reported, tagged {@code client=name} when {@code name} is non-empty. */
        public Builder metrics(Metrics metrics, String name) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            this.clientName = Objects.requireNonNull(name, "name");
            return this;
        }

        public AiClient build() { return new AiClient(this); }
    }
}
