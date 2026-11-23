package cn.managame.ai;

import static cn.managame.FrameworkErrorCodes.AI_CANCELLED;
import static cn.managame.FrameworkErrorCodes.AI_CLOSED;
import static cn.managame.FrameworkErrorCodes.AI_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.AI_PROTOCOL_ERROR;
import static cn.managame.FrameworkErrorCodes.SUCCESS;

import cn.managame.FrameworkErrorCodes;
import cn.managame.ai.internal.AiClientMetrics;
import cn.managame.ai.internal.HttpAiTransport;
import cn.managame.ai.spi.AiTransport;
import cn.managame.metrics.Metrics;
import java.lang.System.Logger.Level;
import java.lang.reflect.Type;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stateless, non-blocking client for calling external AI services (such as a Spring AI or Python agent service) from
 * game server logic.
 *
 * <p>Supports asynchronous unary {@link #call}, Server-Sent Events {@link #stream}, and one-way {@link #notify}.
 * Callbacks and stream listeners never run on the calling thread, and every call returns an {@link AiCall} handle that
 * can be cancelled from any thread (for example when a player disconnects). Applications on {@code managame-runtime}
 * capture {@code runtime.continuation()} before calling and deliver callbacks back onto the caller's route.
 */
public final class AiClient implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("cn.managame.ai.client");
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final AiTransport transport;
    private final boolean ownsTransport;
    private final AiJsonCodec codec;
    private final Duration defaultTimeout;
    private final int maxInFlight;
    private final Map<String, String> defaultHeaders;
    private final List<AiClientInterceptor> interceptors;
    private final Executor asyncExecutor;
    private final AiClientMetrics metrics;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Set<ManagedCall> active = ConcurrentHashMap.newKeySet();

    private AiClient(Builder b) {
        this.codec = b.codec;
        this.defaultTimeout = b.timeout;
        this.maxInFlight = b.maxInFlight;
        this.defaultHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.interceptors = List.copyOf(b.interceptors);
        if (b.transport != null) {
            this.transport = b.transport;
            this.ownsTransport = false;
            this.asyncExecutor = b.callbackExecutor != null ? b.callbackExecutor : ForkJoinPool.commonPool();
        } else {
            boolean ownsHttp = b.httpClient == null;
            HttpClient http = ownsHttp
                    ? HttpClient.newBuilder().connectTimeout(b.connectTimeout).build()
                    : b.httpClient;
            this.transport = new HttpAiTransport(b.baseUrl, http, ownsHttp);
            this.ownsTransport = true;
            this.asyncExecutor = b.callbackExecutor != null
                    ? b.callbackExecutor
                    : http.executor().orElse(ForkJoinPool.commonPool());
        }
        this.metrics = new AiClientMetrics(b.metrics, b.metricsName, inFlight::get);
        LOG.log(Level.INFO, () -> "AiClient started (maxInFlight=" + maxInFlight
                + ", timeout=" + defaultTimeout
                + ", interceptors=" + interceptors.size() + ")");
    }

    public static Builder builder() { return new Builder(); }

    /** Sends an asynchronous unary request to {@code path} and decodes the response into {@code responseType}. */
    public <T> AiCall call(String path, Object body, Class<T> responseType, AiCallback<T> callback) {
        return call(AiRequest.of(path, body), (Type) responseType, callback);
    }

    /** Sends an asynchronous unary request to {@code path} and decodes the response into {@code responseType}. */
    public <T> AiCall call(String path, Object body, Type responseType, AiCallback<T> callback) {
        return call(AiRequest.of(path, body), responseType, callback);
    }

    /** Sends an asynchronous unary {@code request} and decodes the response into {@code responseType}. */
    public <T> AiCall call(AiRequest request, Class<T> responseType, AiCallback<T> callback) {
        return call(request, (Type) responseType, callback);
    }

    /** Sends an asynchronous unary {@code request} and decodes the response into {@code responseType}. */
    public <T> AiCall call(AiRequest request, Type responseType, AiCallback<T> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(callback, "callback");
        metrics.unaryStarted();
        return executeUnary(request, responseType, callback);
    }

    /** Sends an asynchronous streaming request (SSE) to {@code path}. */
    public AiCall stream(String path, Object body, AiStreamListener listener) {
        return stream(AiRequest.of(path, body), listener);
    }

    /** Sends an asynchronous streaming {@code request} (SSE). */
    public AiCall stream(AiRequest request, AiStreamListener listener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        metrics.streamStarted();
        long startNanos = metrics.startTimer();

        if (closed.get()) {
            metrics.finished(startNanos, AI_CLOSED);
            asyncExecutor.execute(() -> listener.onError(AI_CLOSED, "AiClient is closed"));
            return CompletedCall.FAILED;
        }
        if (!acquireSlot()) {
            metrics.finished(startNanos, AI_OVERLOADED);
            LOG.log(Level.WARNING, () -> "AI stream refused (" + request.path() + "): maxInFlight " + maxInFlight + " reached");
            asyncExecutor.execute(() -> listener.onError(AI_OVERLOADED, "AiClient maxInFlight limit reached"));
            return CompletedCall.FAILED;
        }

        Intercepted intercepted = runBefore(request);
        if (intercepted.rejected()) {
            releaseSlot();
            metrics.finished(startNanos, intercepted.errorCode());
            runAfter(intercepted.request(), intercepted.passedCount(), intercepted.errorCode(), intercepted.errorMessage());
            asyncExecutor.execute(() -> listener.onError(intercepted.errorCode(), intercepted.errorMessage()));
            return intercepted.errorCode() == AI_CANCELLED ? CompletedCall.CANCELLED : CompletedCall.FAILED;
        }

        AiTransport.Request encoded;
        try {
            encoded = encodeRequest(intercepted.request());
        } catch (Exception e) {
            releaseSlot();
            String msg = "Failed to encode AI request: " + e.getMessage();
            metrics.finished(startNanos, AI_PROTOCOL_ERROR);
            runAfter(intercepted.request(), intercepted.passedCount(), AI_PROTOCOL_ERROR, msg);
            asyncExecutor.execute(() -> listener.onError(AI_PROTOCOL_ERROR, msg));
            return CompletedCall.FAILED;
        }

        ManagedCall managed = new ManagedCall(intercepted.request(), intercepted.passedCount(), startNanos);
        active.add(managed);
        if (closed.get()) {
            if (managed.finish(AI_CLOSED, "AiClient is closed")) {
                asyncExecutor.execute(() -> listener.onError(AI_CLOSED, "AiClient is closed"));
            }
            return managed;
        }

        AiStreamListener wrapped = new AiStreamListener() {
            @Override public void onChunk(AiChunk chunk) {
                metrics.chunkReceived();
                listener.onChunk(chunk);
            }

            @Override public void onComplete() {
                if (managed.finish(SUCCESS, null)) {
                    listener.onComplete();
                }
            }

            @Override public void onError(int errorCode, String message) {
                if (managed.finish(errorCode, message)) {
                    listener.onError(errorCode, message);
                }
            }
        };

        AiCall delegate = transport.stream(encoded, wrapped);
        managed.attach(delegate);
        return managed;
    }

    /** Sends an asynchronous one-way notification to {@code path}; failures are logged at WARNING level. */
    public AiCall notify(String path, Object body) {
        return notify(AiRequest.of(path, body), null);
    }

    /** Sends an asynchronous one-way {@code request}; failures are logged at WARNING level. */
    public AiCall notify(AiRequest request) {
        return notify(request, null);
    }

    /** Sends an asynchronous one-way {@code request}, delivering success or failure to {@code callback} when non-null. */
    public AiCall notify(AiRequest request, AiCallback<Void> callback) {
        Objects.requireNonNull(request, "request");
        metrics.notifyStarted();
        AiCallback<Void> target = callback != null ? callback : result -> {
            if (!result.isSuccess()) {
                LOG.log(Level.WARNING, () -> "AI notify failed (" + request.path() + "): "
                        + FrameworkErrorCodes.describe(result.code())
                        + (result.message() != null ? " " + result.message() : ""));
            }
        };
        return executeUnary(request, Void.class, target);
    }

    /** Number of calls and streams currently in flight. */
    public int inFlight() { return inFlight.get(); }

    /**
     * Closes the client idempotently: refuses subsequent calls with {@code AI_CLOSED}, cancels in-flight calls and
     * streams, releases owned HTTP resources and removes gauges.
     */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (ManagedCall call : active) {
            call.cancel();
        }
        if (ownsTransport) {
            transport.close();
        }
        metrics.close();
        LOG.log(Level.INFO, "AiClient closed");
    }

    @SuppressWarnings("unchecked")
    private <T> AiCall executeUnary(AiRequest request, Type responseType, AiCallback<T> callback) {
        long startNanos = metrics.startTimer();
        if (closed.get()) {
            metrics.finished(startNanos, AI_CLOSED);
            asyncExecutor.execute(() -> callback.onResult(AiResult.fail(AI_CLOSED, "AiClient is closed")));
            return CompletedCall.FAILED;
        }
        if (!acquireSlot()) {
            metrics.finished(startNanos, AI_OVERLOADED);
            LOG.log(Level.WARNING, () -> "AI call refused (" + request.path() + "): maxInFlight " + maxInFlight + " reached");
            asyncExecutor.execute(() -> callback.onResult(AiResult.fail(AI_OVERLOADED, "AiClient maxInFlight limit reached")));
            return CompletedCall.FAILED;
        }

        Intercepted intercepted = runBefore(request);
        if (intercepted.rejected()) {
            releaseSlot();
            metrics.finished(startNanos, intercepted.errorCode());
            runAfter(intercepted.request(), intercepted.passedCount(), intercepted.errorCode(), intercepted.errorMessage());
            asyncExecutor.execute(() -> callback.onResult(AiResult.fail(intercepted.errorCode(), intercepted.errorMessage())));
            return intercepted.errorCode() == AI_CANCELLED ? CompletedCall.CANCELLED : CompletedCall.FAILED;
        }

        AiTransport.Request encoded;
        try {
            encoded = encodeRequest(intercepted.request());
        } catch (Exception e) {
            releaseSlot();
            String msg = "Failed to encode AI request: " + e.getMessage();
            metrics.finished(startNanos, AI_PROTOCOL_ERROR);
            runAfter(intercepted.request(), intercepted.passedCount(), AI_PROTOCOL_ERROR, msg);
            asyncExecutor.execute(() -> callback.onResult(AiResult.fail(AI_PROTOCOL_ERROR, msg)));
            return CompletedCall.FAILED;
        }

        ManagedCall managed = new ManagedCall(intercepted.request(), intercepted.passedCount(), startNanos);
        active.add(managed);
        if (closed.get()) {
            if (managed.finish(AI_CLOSED, "AiClient is closed")) {
                asyncExecutor.execute(() -> callback.onResult(AiResult.fail(AI_CLOSED, "AiClient is closed")));
            }
            return managed;
        }

        AiCall delegate = transport.send(encoded, (errorCode, rawBody, errorMessage) -> {
            if (errorCode != SUCCESS) {
                if (managed.finish(errorCode, errorMessage)) {
                    deliverUnary(callback, AiResult.fail(errorCode, errorMessage));
                }
                return;
            }
            T decoded;
            try {
                if (responseType == Void.class || responseType == void.class) {
                    decoded = null;
                } else {
                    decoded = (T) codec.decode(rawBody == null ? "" : rawBody, responseType);
                }
            } catch (Exception e) {
                String msg = "Failed to decode AI response: " + e.getMessage();
                if (managed.finish(AI_PROTOCOL_ERROR, msg)) {
                    deliverUnary(callback, AiResult.fail(AI_PROTOCOL_ERROR, msg));
                }
                return;
            }
            if (managed.finish(SUCCESS, null)) {
                deliverUnary(callback, AiResult.ok(decoded));
            }
        });
        managed.attach(delegate);
        return managed;
    }

    private <T> void deliverUnary(AiCallback<T> callback, AiResult<T> result) {
        try {
            callback.onResult(result);
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "AiCallback threw", t);
        }
    }

    private AiTransport.Request encodeRequest(AiRequest request) throws Exception {
        byte[] bytes;
        String contentType;
        if (request.body() == null) {
            bytes = EMPTY_BYTES;
            contentType = null;
        } else if (request.body() instanceof byte[] raw) {
            bytes = raw;
            contentType = "application/octet-stream";
        } else {
            String json = codec.encode(request.body());
            bytes = json == null ? EMPTY_BYTES : json.getBytes(StandardCharsets.UTF_8);
            contentType = JSON_CONTENT_TYPE;
        }
        Map<String, String> mergedHeaders;
        if (defaultHeaders.isEmpty()) {
            mergedHeaders = request.headers();
        } else if (request.headers().isEmpty()) {
            mergedHeaders = defaultHeaders;
        } else {
            Map<String, String> map = new LinkedHashMap<>(defaultHeaders);
            map.putAll(request.headers());
            mergedHeaders = Collections.unmodifiableMap(map);
        }
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        return new AiTransport.Request(request.path(), bytes, contentType, mergedHeaders, timeout);
    }

    private Intercepted runBefore(AiRequest request) {
        if (interceptors.isEmpty()) return new Intercepted(request, 0, SUCCESS, null);
        AiRequest current = request;
        int passed = 0;
        for (AiClientInterceptor interceptor : interceptors) {
            try {
                AiRequest next = interceptor.before(current);
                if (next == null) {
                    return new Intercepted(current, passed, AI_CANCELLED, "Rejected by AiClientInterceptor");
                }
                current = next;
                passed++;
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "AiClientInterceptor.before threw", t);
                return new Intercepted(current, passed, AI_PROTOCOL_ERROR, "Interceptor error: " + t.getMessage());
            }
        }
        return new Intercepted(current, passed, SUCCESS, null);
    }

    private void runAfter(AiRequest request, int passedCount, int errorCode, String message) {
        for (int i = passedCount - 1; i >= 0; i--) {
            try {
                interceptors.get(i).after(request, errorCode, message);
            } catch (Throwable t) {
                LOG.log(Level.ERROR, "AiClientInterceptor.after threw", t);
            }
        }
    }

    private boolean acquireSlot() {
        while (true) {
            int current = inFlight.get();
            if (current >= maxInFlight) return false;
            if (inFlight.compareAndSet(current, current + 1)) return true;
        }
    }

    private void releaseSlot() {
        inFlight.decrementAndGet();
    }

    private record Intercepted(AiRequest request, int passedCount, int errorCode, String errorMessage) {
        boolean rejected() { return errorCode != SUCCESS; }
    }

    private enum CompletedCall implements AiCall {
        FAILED(false),
        CANCELLED(true);

        private final boolean cancelled;
        CompletedCall(boolean cancelled) { this.cancelled = cancelled; }

        @Override public boolean cancel() { return false; }
        @Override public boolean isDone() { return true; }
        @Override public boolean isCancelled() { return cancelled; }
    }

    private final class ManagedCall implements AiCall {
        private final AiRequest request;
        private final int passedInterceptors;
        private final long startNanos;
        private final AtomicBoolean done = new AtomicBoolean();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private volatile AiCall delegate;

        ManagedCall(AiRequest request, int passedInterceptors, long startNanos) {
            this.request = request;
            this.passedInterceptors = passedInterceptors;
            this.startNanos = startNanos;
        }

        void attach(AiCall delegate) {
            this.delegate = delegate;
            if (cancelRequested.get()) delegate.cancel();
        }

        boolean finish(int errorCode, String message) {
            if (!done.compareAndSet(false, true)) return false;
            active.remove(this);
            releaseSlot();
            metrics.finished(startNanos, errorCode);
            runAfter(request, passedInterceptors, errorCode, message);
            return true;
        }

        @Override public boolean cancel() {
            if (done.get() || !cancelRequested.compareAndSet(false, true)) return false;
            AiCall d = delegate;
            if (d != null) {
                d.cancel();
            }
            return true;
        }

        @Override public boolean isDone() { return done.get(); }
        @Override public boolean isCancelled() { return cancelRequested.get(); }
    }

    public static final class Builder {
        private String baseUrl;
        private HttpClient httpClient;
        private AiTransport transport;
        private AiJsonCodec codec = AiJsonCodec.basic();
        private Duration timeout = Duration.ofSeconds(30);
        private Duration connectTimeout = Duration.ofSeconds(10);
        private int maxInFlight = 1024;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final List<AiClientInterceptor> interceptors = new ArrayList<>();
        private Executor callbackExecutor;
        private Metrics metrics = Metrics.noop();
        private String metricsName;

        private Builder() {}

        /** Base URL of the external AI service (for example {@code "http://127.0.0.1:8081/api/ai"}). */
        public Builder baseUrl(String baseUrl) {
            if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl must not be blank");
            this.baseUrl = baseUrl;
            return this;
        }

        /** Shared JDK {@link HttpClient}; not closed by {@link AiClient#close()}. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        /** Custom outbound transport SPI; when set, {@code baseUrl} and {@code httpClient} are unused. */
        public Builder transport(AiTransport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            return this;
        }

        /** JSON codec for encoding request DTOs and decoding response DTOs. Defaults to {@link AiJsonCodec#basic()}. */
        public Builder codec(AiJsonCodec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /** Default per-call timeout. Defaults to 30 seconds. */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.toMillis() < 1) throw new IllegalArgumentException("timeout must be at least one millisecond");
            this.timeout = timeout;
            return this;
        }

        /** TCP connect timeout when {@link AiClient} creates its own {@link HttpClient}. Defaults to 10 seconds. */
        public Builder connectTimeout(Duration connectTimeout) {
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            if (connectTimeout.toMillis() < 1) throw new IllegalArgumentException("connectTimeout must be at least one millisecond");
            this.connectTimeout = connectTimeout;
            return this;
        }

        /** Maximum concurrent calls and streams allowed in flight. Defaults to 1024. */
        public Builder maxInFlight(int maxInFlight) {
            if (maxInFlight <= 0) throw new IllegalArgumentException("maxInFlight must be positive: " + maxInFlight);
            this.maxInFlight = maxInFlight;
            return this;
        }

        /** Default header sent with every request; per-request headers override it. */
        public Builder header(String name, String value) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("header name must not be blank");
            Objects.requireNonNull(value, "header value");
            this.headers.put(name, value);
            return this;
        }

        /** Default headers merged into every request. */
        public Builder headers(Map<String, String> headers) {
            Objects.requireNonNull(headers, "headers");
            headers.forEach(this::header);
            return this;
        }

        /** Registers interceptors run around every outbound call in the given order. */
        public Builder intercept(Collection<? extends AiClientInterceptor> interceptors) {
            Objects.requireNonNull(interceptors, "interceptors");
            for (AiClientInterceptor interceptor : interceptors) {
                this.interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            }
            return this;
        }

        /** Executor used for immediate-failure callbacks when no transport callback runs. */
        public Builder callbackExecutor(Executor callbackExecutor) {
            this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
            return this;
        }

        /** Metrics sink, {@link Metrics#noop()} by default. */
        public Builder metrics(Metrics metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            this.metricsName = null;
            return this;
        }

        /** Metrics sink with a {@code client} tag identifying this client when an application holds several. */
        public Builder metrics(Metrics metrics, String clientName) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            if (clientName == null || clientName.isBlank()) {
                throw new IllegalArgumentException("clientName must not be blank");
            }
            this.metricsName = clientName;
            return this;
        }

        public AiClient build() {
            if (transport == null && baseUrl == null) {
                throw new IllegalStateException("baseUrl or transport must be configured");
            }
            return new AiClient(this);
        }
    }
}
