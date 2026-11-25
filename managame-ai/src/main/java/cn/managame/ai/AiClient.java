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
import io.netty.util.Timeout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Single non-blocking HTTP and W3C SSE client shared across all AI-enabled business modules of a game server:
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
 * <p>One {@link AiClient} serves the entire server process with a single HTTP connection pool and a shared daemon
 * timing wheel covering full end-to-end deadlines (both response headers and response body) for unary calls, SSE
 * streams, and one-way notifications. Callbacks and stream listeners never run on the calling thread or on I/O selector
 * / timer threads: response decoding happens off the business route before dispatching the decoded result through
 * {@link AiDispatcher}.
 */
public final class AiClient implements AutoCloseable {
    /** Default HTTP header carrying the caller's per-request timeout budget in milliseconds. */
    public static final String TIMEOUT_HEADER = "X-Ai-Timeout-Ms";

    private static final System.Logger LOG = System.getLogger("cn.managame.ai.client");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_IN_FLIGHT = 1024;
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_ERROR_BODY_CHARS = 512;
    private static final byte[] EMPTY_BYTES = {};

    private final String baseUrl;
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
    private final Map<String, String> defaultHeaders;
    private final List<DynamicHeader> dynamicHeaders;
    private final List<PropagatedHeader> propagated;
    private final HttpClient httpClient;
    private final boolean ownsHttpClient;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger notifyInFlight = new AtomicInteger();
    private final Set<InFlightCall> active = ConcurrentHashMap.newKeySet();
    private final AiClientMetrics metrics;
    private volatile boolean closed;

    private AiClient(Builder b) {
        this.baseUrl = normalizeBaseUrl(b.baseUri);
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
        this.defaultHeaders = Map.copyOf(b.headers);
        this.dynamicHeaders = List.copyOf(b.dynamicHeaders);
        this.propagated = List.copyOf(b.propagated);
        if (b.httpClient != null) {
            this.httpClient = b.httpClient;
            this.ownsHttpClient = false;
        } else {
            HttpClient.Version version = "https".equalsIgnoreCase(b.baseUri.getScheme())
                    ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1;
            this.httpClient = HttpClient.newBuilder()
                    .version(version)
                    .connectTimeout(b.connectTimeout)
                    .executor(task -> Thread.ofVirtual().name("managame-ai-io-", 0).start(task))
                    .build();
            this.ownsHttpClient = true;
        }
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
        HttpRequest httpRequest = buildHttpRequest(request, ambient, "application/json");
        metrics.callStarted();
        if (closed) return failImmediately(routeExecutor, AI_CLOSED, "AiClient is closed", callback);
        if (!tryAcquireSlot(inFlight, maxInFlight)) {
            LOG.log(Level.WARNING, () -> "AiClient overloaded (" + maxInFlight + " in flight) for " + request.path());
            return failImmediately(routeExecutor, AI_OVERLOADED, "Max in-flight AI calls reached (" + maxInFlight + ")", callback);
        }
        long startNanos = metrics.startTimer();
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        UnaryCall<T> call = new UnaryCall<>(startNanos, routeExecutor, callback);
        active.add(call);
        if (closed) {
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
        CompletableFuture<HttpResponse<byte[]>> future = httpClient.sendAsync(
                httpRequest, info -> new BoundedBytesBodySubscriber(maxResponseBytes, call::attachSubscription));
        call.attach(future);
        future.whenComplete((response, error) -> completeUnary(request, responseType, call, response, error));
        return call;
    }

    private <T> void completeUnary(AiRequest request, Type responseType, UnaryCall<T> call,
            HttpResponse<byte[]> response, Throwable error) {
        if (call.isDone()) return;
        if (error != null) {
            int code = mapThrowable(error);
            if (code == AI_TIMEOUT) LOG.log(Level.WARNING, () -> "AI call timed out: " + request.path());
            call.finish(AiResult.fail(code, describeError(unwrap(error))));
            return;
        }
        int status = response.statusCode();
        byte[] body = response.body();
        if (!is2xx(status)) {
            int code = mapStatus(status);
            if (code == AI_OVERLOADED || code == AI_TIMEOUT) {
                LOG.log(Level.WARNING, () -> "AI call " + request.path() + " failed with HTTP " + status);
            }
            call.finish(AiResult.fail(code, status, truncateError(body, status)));
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            T decoded = (T) decodeBody(body, responseType);
            call.finish(AiResult.ok(status, decoded));
        } catch (Throwable cause) {
            LOG.log(Level.ERROR, "Failed to decode AI response for " + request.path(), cause);
            call.finish(AiResult.fail(AI_PROTOCOL_ERROR, status, describeError(cause)));
        }
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
        Executor routeExecutor = Objects.requireNonNull(dispatcher.capture(request.context()), "captured executor");
        Metadata ambient = dispatcher.metadata(request.context());
        HttpRequest httpRequest = buildHttpRequest(request, ambient, "text/event-stream");
        metrics.streamStarted();
        if (closed) return failStreamImmediately(routeExecutor, AI_CLOSED, "AiClient is closed", listener);
        if (!tryAcquireSlot(inFlight, maxInFlight)) {
            LOG.log(Level.WARNING, () -> "AiClient overloaded (" + maxInFlight + " in flight) for stream " + request.path());
            return failStreamImmediately(routeExecutor, AI_OVERLOADED, "Max in-flight AI calls reached (" + maxInFlight + ")", listener);
        }
        long startNanos = metrics.startTimer();
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        Duration idleTimeout = request.idleTimeout() != null ? request.idleTimeout() : defaultStreamIdleTimeout;
        StreamCall call = new StreamCall(startNanos, routeExecutor, request.path(), listener);
        active.add(call);
        if (closed) {
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
        HttpResponse.BodyHandler<byte[]> bodyHandler = info -> {
            int status = info.statusCode();
            if (!is2xx(status)) {
                return new BoundedBytesBodySubscriber(Math.min(maxResponseBytes, 4096), call::attachSubscription);
            }
            return HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.fromSubscriber(new SseBodySubscriber(call, status, maxStreamEventChars)),
                    ignored -> EMPTY_BYTES);
        };
        CompletableFuture<HttpResponse<byte[]>> future = httpClient.sendAsync(httpRequest, bodyHandler);
        call.attach(future);
        future.whenComplete((response, error) -> completeStream(request, call, response, error));
        return call;
    }

    private void completeStream(AiRequest request, StreamCall call, HttpResponse<byte[]> response, Throwable error) {
        if (call.isDone()) return;
        if (error != null) {
            int code = mapThrowable(error);
            if (code == AI_TIMEOUT) LOG.log(Level.WARNING, () -> "AI stream timed out: " + request.path());
            call.finish(AiResult.fail(code, describeError(unwrap(error))));
            return;
        }
        int status = response.statusCode();
        if (!is2xx(status)) {
            call.finish(AiResult.fail(mapStatus(status), status, truncateError(response.body(), status)));
        } else {
            call.finish(AiResult.ok(status, null));
        }
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
        HttpRequest httpRequest = buildHttpRequest(request, ambient, "application/json");
        metrics.notifyStarted();
        if (closed) {
            metrics.failed(AI_CLOSED);
            LOG.log(Level.WARNING, () -> "AI notify refused (closed): " + request.path());
            return WriteStatus.INACTIVE;
        }
        if (!tryAcquireSlot(notifyInFlight, maxNotifyInFlight)) {
            metrics.failed(AI_OVERLOADED);
            LOG.log(Level.WARNING, () -> "AI notify refused (overloaded): " + request.path());
            return WriteStatus.NOT_WRITABLE;
        }
        if (closed) {
            notifyInFlight.decrementAndGet();
            metrics.failed(AI_CLOSED);
            return WriteStatus.INACTIVE;
        }
        long startNanos = metrics.startTimer();
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        if (LOG.isLoggable(Level.DEBUG)) LOG.log(Level.DEBUG, () -> "AI notify " + request.method() + " " + httpRequest.uri());
        AtomicBoolean timedOut = new AtomicBoolean();
        CompletableFuture<HttpResponse<Void>> future = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.discarding());
        Timeout timerHandle = AiTimer.newTimeout(t -> {
            if (timedOut.compareAndSet(false, true)) future.cancel(true);
        }, timeout.toNanos());
        future.whenComplete((response, error) -> {
            timerHandle.cancel();
            notifyInFlight.decrementAndGet();
            metrics.recordLatency(startNanos);
            if (error != null) {
                int code = timedOut.get() ? AI_TIMEOUT : mapThrowable(error);
                metrics.failed(code);
                LOG.log(Level.WARNING, () -> "AI notify failed for " + request.path() + ": " + describeError(unwrap(error)));
            } else if (!is2xx(response.statusCode())) {
                int code = mapStatus(response.statusCode());
                metrics.failed(code);
                LOG.log(Level.WARNING, () -> "AI notify failed for " + request.path() + " with HTTP " + response.statusCode());
            }
        });
        return WriteStatus.ACCEPTED;
    }

    /** Current number of in-flight {@link #call} and {@link #stream} requests. */
    public int inFlight() { return inFlight.get(); }

    /** Current number of in-flight one-way {@link #notify} requests. */
    public int notifyInFlight() { return notifyInFlight.get(); }

    public boolean isClosed() { return closed; }

    /**
     * Closes the client: subsequent calls fail with {@link cn.managame.FrameworkErrorCodes#AI_CLOSED}, in-flight calls
     * and streams are aborted with {@code AI_CLOSED} (cancelling their timers and HTTP subscriptions), and the internal
     * {@link HttpClient} (if owned) is shut down. Safe to call more than once.
     */
    @Override public void close() {
        if (closed) return;
        closed = true;
        LOG.log(Level.INFO, () -> "Closing AiClient for " + baseUrl);
        for (InFlightCall call : active) {
            call.abort(AI_CLOSED, 0, "AiClient closed");
        }
        if (ownsHttpClient) httpClient.shutdownNow();
        metrics.close();
    }

    private HttpRequest buildHttpRequest(AiRequest request, Metadata ambientMetadata, String accept) {
        try {
            URI uri = resolveUri(request.path());
            Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
            Object body = request.body();
            HttpRequest.BodyPublisher publisher;
            if (body == null) {
                publisher = HttpRequest.BodyPublishers.noBody();
            } else if (body instanceof String s) {
                publisher = HttpRequest.BodyPublishers.ofString(s, StandardCharsets.UTF_8);
            } else if (body instanceof byte[] bytes) {
                publisher = HttpRequest.BodyPublishers.ofByteArray(bytes);
            } else {
                publisher = HttpRequest.BodyPublishers.ofByteArray(codec.encodeBytes(body));
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .setHeader("Accept", accept);
            if (body != null) {
                builder.setHeader("Content-Type", "application/json; charset=UTF-8");
            }
            if (!timeoutHeader.isEmpty()) {
                String millis = request.timeout() == null
                        ? defaultTimeoutMillis
                        : Long.toString(Math.max(1L, timeout.toMillis()));
                builder.setHeader(timeoutHeader, millis);
            }
            for (Map.Entry<String, String> entry : defaultHeaders.entrySet()) {
                builder.setHeader(entry.getKey(), entry.getValue());
            }
            for (DynamicHeader dynamic : dynamicHeaders) {
                String value = dynamic.supplier().get();
                if (value != null) builder.setHeader(dynamic.name(), value);
            }
            if (ambientMetadata != null) propagateHeaders(builder, ambientMetadata);
            propagateHeaders(builder, request.metadata());
            for (Map.Entry<String, String> entry : request.headers().entrySet()) {
                builder.setHeader(entry.getKey(), entry.getValue());
            }
            builder.method(request.method(), publisher);
            return builder.build();
        } catch (IllegalArgumentException badArg) {
            throw badArg;
        } catch (Throwable cause) {
            throw new IllegalArgumentException(
                    "Failed to build AI request for " + request.path() + ": " + describeError(cause), cause);
        }
    }

    private void propagateHeaders(HttpRequest.Builder builder, Metadata metadata) {
        if (metadata.isEmpty() || propagated.isEmpty()) return;
        for (PropagatedHeader entry : propagated) {
            if (metadata.contains(entry.key())) {
                Object value = metadata.get(entry.key());
                if (value != null) builder.setHeader(entry.header(), value.toString());
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

    private URI resolveUri(String path) {
        if (path.isEmpty()) return URI.create(baseUrl);
        if (path.startsWith("/") || path.startsWith("?")) return URI.create(baseUrl + path);
        return URI.create(baseUrl + "/" + path);
    }

    private static String normalizeBaseUrl(URI uri) {
        String raw = uri.toString();
        while (raw.endsWith("/") && raw.length() > uri.getScheme().length() + 3) {
            raw = raw.substring(0, raw.length() - 1);
        }
        return raw;
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

    private AiCall failStreamImmediately(Executor executor, int code, String message, AiStreamListener listener) {
        metrics.failed(code);
        AiResult<Void> result = AiResult.fail(code, message);
        dispatchCallback(executor, () -> deliverComplete(listener, result));
        return CompletedCall.INSTANCE;
    }

    private static <T> void deliver(AiCallback<T> callback, AiResult<T> result) {
        try {
            callback.onResponse(result);
        } catch (Throwable cause) {
            LOG.log(Level.ERROR, "Uncaught exception in AiCallback", cause);
        }
    }

    private static void deliverComplete(AiStreamListener listener, AiResult<Void> result) {
        try {
            listener.onComplete(result);
        } catch (Throwable cause) {
            LOG.log(Level.ERROR, "Uncaught exception in AiStreamListener.onComplete", cause);
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
        if (root instanceof ResponseTooLargeException) return AI_PROTOCOL_ERROR;
        if (root instanceof HttpTimeoutException) return AI_TIMEOUT;
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

    private abstract class InFlightCall implements AiCall {
        private final long startNanos;
        private final AtomicBoolean done = new AtomicBoolean();
        private final AtomicReference<CompletableFuture<?>> httpFuture = new AtomicReference<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicReference<Timeout> totalTimerRef = new AtomicReference<>();

        InFlightCall(long startNanos) { this.startNanos = startNanos; }

        void attach(CompletableFuture<?> future) {
            httpFuture.set(future);
            if (done.get()) future.cancel(true);
        }

        void attachSubscription(Flow.Subscription sub) {
            subscription.set(sub);
            if (done.get()) sub.cancel();
        }

        void attachTimer(Timeout timeout) {
            totalTimerRef.set(timeout);
            if (done.get()) timeout.cancel();
        }

        boolean markDone() {
            if (!done.compareAndSet(false, true)) return false;
            active.remove(this);
            inFlight.decrementAndGet();
            Timeout total = totalTimerRef.getAndSet(null);
            if (total != null) total.cancel();
            metrics.recordLatency(startNanos);
            return true;
        }

        void cancelTransport() {
            Flow.Subscription sub = subscription.get();
            if (sub != null) sub.cancel();
            CompletableFuture<?> f = httpFuture.get();
            if (f != null) f.cancel(true);
        }

        abstract boolean abort(int code, int status, String message);

        @Override public boolean isDone() { return done.get(); }

        @Override public boolean cancel() { return abort(AI_CANCELLED, 0, "Cancelled"); }
    }

    private final class UnaryCall<T> extends InFlightCall {
        private final Executor routeExecutor;
        private final AiCallback<T> callback;

        UnaryCall(long startNanos, Executor routeExecutor, AiCallback<T> callback) {
            super(startNanos);
            this.routeExecutor = routeExecutor;
            this.callback = callback;
        }

        void finish(AiResult<T> result) {
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

    private final class StreamCall extends InFlightCall {
        private final Executor routeExecutor;
        private final String path;
        private final AiStreamListener listener;
        private final ConcurrentLinkedQueue<AiStreamEvent> eventQueue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger wip = new AtomicInteger();
        private final AtomicBoolean completedDelivered = new AtomicBoolean();
        private final AtomicReference<Timeout> idleTimerRef = new AtomicReference<>();
        private volatile boolean aborted;
        private volatile AiResult<Void> terminalResult;
        private volatile long lastActivityNanos;

        StreamCall(long startNanos, Executor routeExecutor, String path, AiStreamListener listener) {
            super(startNanos);
            this.routeExecutor = routeExecutor;
            this.path = path;
            this.listener = listener;
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

        void touch() { lastActivityNanos = System.nanoTime(); }

        void emit(int status, AiStreamEvent event) {
            if (isDone()) return;
            if ("error".equals(event.event())) {
                String errMsg = event.data().isBlank() ? "SSE error event" : event.data();
                LOG.log(Level.WARNING, () -> "AI stream received error event on " + path + ": " + errMsg);
                finishAndCancel(AiResult.fail(AI_UNAVAILABLE, status, errMsg));
                return;
            }
            eventQueue.offer(event);
            scheduleDrain();
        }

        void finish(AiResult<Void> result) {
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
                    AiStreamEvent ev = eventQueue.poll();
                    if (ev == null) break;
                    metrics.streamEvent();
                    try {
                        listener.onEvent(ev);
                    } catch (Throwable cause) {
                        LOG.log(Level.ERROR, "Uncaught exception in AiStreamListener.onEvent", cause);
                    }
                }
                if (aborted) eventQueue.clear();
                AiResult<Void> done = terminalResult;
                if (done != null && (aborted || eventQueue.isEmpty())) {
                    if (completedDelivered.compareAndSet(false, true)) {
                        deliverComplete(listener, done);
                    }
                    return;
                }
                missed = wip.addAndGet(-missed);
                if (missed == 0) return;
            }
        }

        private void cancelIdleTimer() {
            Timeout idle = idleTimerRef.getAndSet(null);
            if (idle != null) idle.cancel();
        }
    }

    private final class SseBodySubscriber implements Flow.Subscriber<List<ByteBuffer>> {
        private final StreamCall call;
        private final int status;
        private final SseParser parser;
        private Flow.Subscription subscription;

        SseBodySubscriber(StreamCall call, int status, int maxEventChars) {
            this.call = call;
            this.status = status;
            this.parser = new SseParser(event -> call.emit(status, event), maxEventChars);
        }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            call.attachSubscription(subscription);
            if (!call.isDone()) subscription.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            if (call.isDone()) {
                subscription.cancel();
                return;
            }
            call.touch();
            try {
                for (ByteBuffer buffer : buffers) {
                    if (call.isDone()) {
                        subscription.cancel();
                        return;
                    }
                    parser.feed(buffer);
                }
            } catch (IllegalArgumentException oversized) {
                subscription.cancel();
                LOG.log(Level.WARNING, () -> "AI SSE stream aborted: " + oversized.getMessage());
                call.abort(AI_PROTOCOL_ERROR, status, oversized.getMessage());
                return;
            }
            if (!call.isDone()) subscription.request(1);
        }

        @Override public void onError(Throwable throwable) {
            if (!call.isDone()) {
                call.finish(AiResult.fail(mapThrowable(throwable), describeError(unwrap(throwable))));
            }
        }

        @Override public void onComplete() {
            if (!call.isDone()) {
                try {
                    parser.finish();
                    call.finish(AiResult.ok(status, null));
                } catch (IllegalArgumentException oversized) {
                    call.finish(AiResult.fail(AI_PROTOCOL_ERROR, status, oversized.getMessage()));
                }
            }
        }
    }

    private static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException(String message) { super(message); }
    }

    private static final class BoundedBytesBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final Consumer<Flow.Subscription> onSubscribeHook;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private byte[] firstChunk;
        private ByteArrayOutputStream multiChunk;
        private int totalBytes;

        BoundedBytesBodySubscriber(int maxBytes, Consumer<Flow.Subscription> onSubscribeHook) {
            this.maxBytes = maxBytes;
            this.onSubscribeHook = onSubscribeHook;
        }

        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (onSubscribeHook != null) onSubscribeHook.accept(subscription);
            subscription.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buf : buffers) {
                int remaining = buf.remaining();
                if (remaining == 0) continue;
                if (remaining > maxBytes - totalBytes) {
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLargeException(
                            "AI response body exceeded maxResponseBytes (" + maxBytes + ")"));
                    return;
                }
                totalBytes += remaining;
                if (firstChunk == null && multiChunk == null) {
                    byte[] chunk = new byte[remaining];
                    buf.get(chunk);
                    firstChunk = chunk;
                } else {
                    if (multiChunk == null) {
                        multiChunk = new ByteArrayOutputStream(Math.max(256, firstChunk.length + remaining));
                        multiChunk.write(firstChunk, 0, firstChunk.length);
                        firstChunk = null;
                    }
                    if (buf.hasArray()) {
                        multiChunk.write(buf.array(), buf.arrayOffset() + buf.position(), remaining);
                        buf.position(buf.limit());
                    } else {
                        byte[] tmp = new byte[remaining];
                        buf.get(tmp);
                        multiChunk.write(tmp, 0, remaining);
                    }
                }
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable throwable) { body.completeExceptionally(throwable); }

        @Override public void onComplete() {
            if (totalBytes == 0) body.complete(EMPTY_BYTES);
            else if (firstChunk != null) body.complete(firstChunk);
            else body.complete(multiChunk.toByteArray());
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
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final List<DynamicHeader> dynamicHeaders = new ArrayList<>();
        private final List<PropagatedHeader> propagated = new ArrayList<>();
        private HttpClient httpClient;
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

        /** TCP connect timeout when using the built-in HttpClient; 5 seconds by default. */
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

        /** Shares an existing {@link HttpClient}; it is not shut down when this {@link AiClient} closes. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
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
