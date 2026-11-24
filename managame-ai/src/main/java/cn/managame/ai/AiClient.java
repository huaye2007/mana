package cn.managame.ai;

import static cn.managame.FrameworkErrorCodes.AI_CANCELLED;
import static cn.managame.FrameworkErrorCodes.AI_CLOSED;
import static cn.managame.FrameworkErrorCodes.AI_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.AI_PROTOCOL_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.AI_UNAVAILABLE;

import cn.managame.FrameworkErrorCodes;
import cn.managame.Metadata;
import cn.managame.MetadataKey;
import cn.managame.ai.internal.AiClientMetrics;
import cn.managame.ai.internal.SseParser;
import cn.managame.metrics.Metrics;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin, non-blocking HTTP and W3C SSE client for calling an external AI service (such as one built with Spring AI or
 * LangChain) without blocking game threads:
 *
 * <pre>{@code
 * AiClient ai = AiClient.builder("http://127.0.0.1:8081/api/ai")
 *         .codec(jsonCodec)
 *         .timeout(Duration.ofSeconds(30))
 *         .maxInFlight(128)
 *         .header("Authorization", "Bearer " + token)
 *         .propagate(TRACE_ID, "X-Trace-Id")
 *         .metrics(metrics, "npc")
 *         .build();
 *
 * AiCall call = ai.call("/npc/chat", new NpcTalk(roleId, text), NpcReply.class, result -> { ... });
 * }</pre>
 *
 * <p>Callbacks and stream listeners never run on the calling thread. When used with {@code managame-runtime}, capture
 * {@code runtime.continuation()} before calling and resume game state inside {@code continuation.execute(...)}.
 * Requests exceeding {@code maxInFlight} fail with {@link cn.managame.FrameworkErrorCodes#AI_OVERLOADED}; calling
 * {@link #close()} fails new calls with {@link cn.managame.FrameworkErrorCodes#AI_CLOSED} and aborts in-flight ones.
 */
public final class AiClient implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("cn.managame.ai.client");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_IN_FLIGHT = 256;
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_ERROR_BODY_CHARS = 512;

    private final String baseUrl;
    private final AiCodec codec;
    private final Duration defaultTimeout;
    private final Duration defaultStreamIdleTimeout;
    private final int maxInFlight;
    private final int maxResponseBytes;
    private final int maxStreamEventChars;
    private final Map<String, String> defaultHeaders;
    private final List<PropagatedHeader> propagated;
    private final HttpClient httpClient;
    private final boolean ownsHttpClient;
    private final Executor callbackExecutor;
    private final AtomicReference<ScheduledExecutorService> streamScheduler = new AtomicReference<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Set<InFlightCall> active = ConcurrentHashMap.newKeySet();
    private final AiClientMetrics metrics;
    private volatile boolean closed;

    private AiClient(Builder b) {
        this.baseUrl = normalizeBaseUrl(b.baseUri);
        this.codec = b.codec;
        this.defaultTimeout = b.timeout;
        this.defaultStreamIdleTimeout = b.streamIdleTimeout;
        this.maxInFlight = b.maxInFlight;
        this.maxResponseBytes = b.maxResponseBytes;
        this.maxStreamEventChars = b.maxStreamEventChars;
        this.defaultHeaders = Map.copyOf(b.headers);
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
                    .build();
            this.ownsHttpClient = true;
        }
        this.callbackExecutor = task -> Thread.ofVirtual().name("managame-ai-cb-", 0).start(task);
        this.metrics = new AiClientMetrics(b.metrics, b.clientName, inFlight::get);
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

    /** Sends an asynchronous POST request and decodes a 2xx response into {@code responseType}. */
    public <T> AiCall call(AiRequest request, Class<T> responseType, AiCallback<T> callback) {
        return call(request, (Type) responseType, callback);
    }

    /** Sends an asynchronous POST request and decodes a 2xx response into the generic {@code responseType}. */
    public <T> AiCall call(AiRequest request, Type responseType, AiCallback<T> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(callback, "callback");
        metrics.callStarted();
        if (closed) return failImmediately(AI_CLOSED, "AiClient is closed", callback);
        if (!tryAcquireSlot()) {
            LOG.log(Level.WARNING, () -> "AiClient overloaded (" + maxInFlight + " in flight) for " + request.path());
            return failImmediately(AI_OVERLOADED, "Max in-flight AI calls reached (" + maxInFlight + ")", callback);
        }
        long startNanos = metrics.startTimer();
        HttpRequest httpRequest;
        try {
            httpRequest = buildHttpRequest(request, "application/json");
        } catch (Throwable cause) {
            inFlight.decrementAndGet();
            LOG.log(Level.ERROR, "Failed to encode AI request for " + request.path(), cause);
            return failImmediately(AI_PROTOCOL_ERROR, describeError(cause), callback);
        }
        UnaryCall<T> call = new UnaryCall<>(startNanos, callback);
        active.add(call);
        if (closed) {
            call.abort(AI_CLOSED, "AiClient is closed");
            return call;
        }
        if (LOG.isLoggable(Level.DEBUG)) LOG.log(Level.DEBUG, () -> "AI call " + request.method() + " " + httpRequest.uri());
        Thread caller = Thread.currentThread();
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                httpRequest, info -> new BoundedStringBodySubscriber(maxResponseBytes));
        call.attach(future);
        future.whenComplete((response, error) -> {
            if (Thread.currentThread() == caller) {
                callbackExecutor.execute(() -> completeUnary(request, responseType, call, response, error));
            } else {
                completeUnary(request, responseType, call, response, error);
            }
        });
        return call;
    }

    private <T> void completeUnary(AiRequest request, Type responseType, UnaryCall<T> call,
            HttpResponse<String> response, Throwable error) {
        if (call.isDone()) return;
        if (error != null) {
            int code = mapThrowable(error);
            if (code == AI_TIMEOUT) LOG.log(Level.WARNING, () -> "AI call timed out: " + request.path());
            call.finish(AiResult.fail(code, describeError(unwrap(error))));
            return;
        }
        int status = response.statusCode();
        String body = response.body();
        if (!is2xx(status)) {
            int code = mapStatus(status);
            if (code == AI_OVERLOADED || code == AI_TIMEOUT) {
                LOG.log(Level.WARNING, () -> "AI call " + request.path() + " failed with HTTP " + status);
            }
            call.finish(AiResult.fail(code, status, truncate(body, status)));
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
    public AiCall stream(String path, Object body, AiStreamListener listener) {
        return stream(AiRequest.of(path, body), listener);
    }

    /** Sends an asynchronous POST request expecting a {@code text/event-stream} (W3C SSE) response. */
    public AiCall stream(AiRequest request, AiStreamListener listener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        metrics.streamStarted();
        if (closed) return failStreamImmediately(AI_CLOSED, "AiClient is closed", listener);
        if (!tryAcquireSlot()) {
            LOG.log(Level.WARNING, () -> "AiClient overloaded (" + maxInFlight + " in flight) for stream " + request.path());
            return failStreamImmediately(AI_OVERLOADED, "Max in-flight AI calls reached (" + maxInFlight + ")", listener);
        }
        long startNanos = metrics.startTimer();
        HttpRequest httpRequest;
        try {
            httpRequest = buildHttpRequest(request, "text/event-stream");
        } catch (Throwable cause) {
            inFlight.decrementAndGet();
            LOG.log(Level.ERROR, "Failed to encode AI stream request for " + request.path(), cause);
            return failStreamImmediately(AI_PROTOCOL_ERROR, describeError(cause), listener);
        }
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        Duration idleTimeout = request.idleTimeout() != null ? request.idleTimeout() : defaultStreamIdleTimeout;
        StreamCall call = new StreamCall(startNanos, request.path(), listener);
        active.add(call);
        if (closed) {
            call.abort(AI_CLOSED, "AiClient is closed");
            return call;
        }
        if (LOG.isLoggable(Level.DEBUG)) LOG.log(Level.DEBUG, () -> "AI stream " + request.method() + " " + httpRequest.uri());
        try {
            ScheduledFuture<?> timer = scheduler().schedule(
                    () -> {
                        if (call.abort(AI_TIMEOUT, "AI stream timed out after " + timeout)) {
                            LOG.log(Level.WARNING, () -> "AI stream timed out: " + request.path());
                        }
                    },
                    timeout.toNanos(), TimeUnit.NANOSECONDS);
            call.attachTimer(timer);
            if (idleTimeout != null) {
                call.startIdleTimer(idleTimeout.toNanos(), idleTimeout);
            }
        } catch (RejectedExecutionException closedRace) {
            call.abort(AI_CLOSED, "AiClient is closed");
            return call;
        }
        HttpResponse.BodyHandler<String> bodyHandler = info -> {
            int status = info.statusCode();
            if (!is2xx(status)) {
                return new BoundedStringBodySubscriber(Math.min(maxResponseBytes, 4096));
            }
            return HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.fromSubscriber(new SseBodySubscriber(call, status, maxStreamEventChars)),
                    ignored -> "");
        };
        Thread caller = Thread.currentThread();
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(httpRequest, bodyHandler);
        call.attach(future);
        future.whenComplete((response, error) -> {
            if (Thread.currentThread() == caller) {
                callbackExecutor.execute(() -> completeStream(request, call, response, error));
            } else {
                completeStream(request, call, response, error);
            }
        });
        return call;
    }

    private void completeStream(AiRequest request, StreamCall call, HttpResponse<String> response, Throwable error) {
        if (call.isDone()) return;
        if (error != null) {
            int code = mapThrowable(error);
            if (code == AI_TIMEOUT) LOG.log(Level.WARNING, () -> "AI stream timed out: " + request.path());
            call.finish(AiResult.fail(code, describeError(unwrap(error))));
            return;
        }
        int status = response.statusCode();
        if (!is2xx(status)) {
            call.finish(AiResult.fail(mapStatus(status), status, truncate(response.body(), status)));
        } else {
            call.finish(AiResult.ok(status, null));
        }
    }

    /**
     * Sends a one-way asynchronous POST notification (such as a game event) without waiting for a response body.
     * Returns {@code true} if accepted, or {@code false} if the client is closed, overloaded, or encoding failed.
     */
    public boolean notify(String path, Object body) {
        return notify(AiRequest.of(path, body));
    }

    /**
     * Sends a one-way asynchronous POST notification without waiting for a response body. Returns {@code true} if
     * accepted, or {@code false} if the client is closed, overloaded, or encoding failed.
     */
    public boolean notify(AiRequest request) {
        Objects.requireNonNull(request, "request");
        metrics.notifyStarted();
        if (closed) {
            metrics.failed(AI_CLOSED);
            LOG.log(Level.WARNING, () -> "AI notify refused (closed): " + request.path());
            return false;
        }
        if (!tryAcquireSlot()) {
            metrics.failed(AI_OVERLOADED);
            LOG.log(Level.WARNING, () -> "AI notify refused (overloaded): " + request.path());
            return false;
        }
        long startNanos = metrics.startTimer();
        HttpRequest httpRequest;
        try {
            httpRequest = buildHttpRequest(request, "application/json");
        } catch (Throwable cause) {
            inFlight.decrementAndGet();
            metrics.failed(AI_PROTOCOL_ERROR);
            LOG.log(Level.ERROR, "Failed to encode AI notify for " + request.path(), cause);
            return false;
        }
        if (LOG.isLoggable(Level.DEBUG)) LOG.log(Level.DEBUG, () -> "AI notify " + request.method() + " " + httpRequest.uri());
        NotifyCall call = new NotifyCall(startNanos);
        active.add(call);
        if (closed) {
            call.abort(AI_CLOSED, "AiClient is closed");
            return false;
        }
        CompletableFuture<HttpResponse<Void>> future = httpClient.sendAsync(
                httpRequest, HttpResponse.BodyHandlers.discarding());
        call.attach(future);
        future.whenComplete((response, error) -> {
            if (!call.markDone()) return;
            if (error != null) {
                int code = mapThrowable(error);
                metrics.failed(code);
                LOG.log(Level.WARNING, () -> "AI notify failed for " + request.path() + ": " + describeError(unwrap(error)));
            } else if (!is2xx(response.statusCode())) {
                int code = mapStatus(response.statusCode());
                metrics.failed(code);
                LOG.log(Level.WARNING, () -> "AI notify failed for " + request.path() + " with HTTP " + response.statusCode());
            }
        });
        return true;
    }

    /** Current number of in-flight calls, streams and notifications. */
    public int inFlight() { return inFlight.get(); }

    public boolean isClosed() { return closed; }

    /**
     * Closes the client: subsequent calls fail with {@link cn.managame.FrameworkErrorCodes#AI_CLOSED}, in-flight calls
     * and streams are aborted with {@code AI_CLOSED}, and the internal {@link HttpClient} (if owned) is shut down.
     * Safe to call more than once.
     */
    @Override public void close() {
        if (closed) return;
        closed = true;
        LOG.log(Level.INFO, () -> "Closing AiClient for " + baseUrl);
        for (InFlightCall call : active) {
            call.abort(AI_CLOSED, "AiClient closed");
        }
        ScheduledExecutorService sched = streamScheduler.getAndSet(null);
        if (sched != null) sched.shutdownNow();
        if (ownsHttpClient) httpClient.shutdownNow();
        metrics.close();
    }

    private HttpRequest buildHttpRequest(AiRequest request, String accept) throws Exception {
        URI uri = resolveUri(request.path());
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", accept);
        for (Map.Entry<String, String> entry : defaultHeaders.entrySet()) {
            builder.setHeader(entry.getKey(), entry.getValue());
        }
        Metadata metadata = request.metadata();
        if (!metadata.isEmpty()) {
            for (PropagatedHeader entry : propagated) {
                if (metadata.contains(entry.key())) {
                    Object value = metadata.get(entry.key());
                    if (value != null) builder.setHeader(entry.header(), value.toString());
                }
            }
        }
        for (Map.Entry<String, String> entry : request.headers().entrySet()) {
            builder.setHeader(entry.getKey(), entry.getValue());
        }
        Object body = request.body();
        HttpRequest.BodyPublisher publisher;
        if (body == null) {
            publisher = HttpRequest.BodyPublishers.noBody();
        } else {
            String encoded = body instanceof String s ? s : codec.encode(body);
            builder.header("Content-Type", "application/json; charset=UTF-8");
            publisher = HttpRequest.BodyPublishers.ofString(encoded, StandardCharsets.UTF_8);
        }
        builder.method(request.method(), publisher);
        return builder.build();
    }

    private Object decodeBody(String body, Type responseType) throws Exception {
        if (responseType == Void.class) return null;
        if (responseType == String.class) return body == null ? "" : body;
        return codec.decode(body, responseType);
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

    private boolean tryAcquireSlot() {
        while (true) {
            int current = inFlight.get();
            if (current >= maxInFlight) return false;
            if (inFlight.compareAndSet(current, current + 1)) return true;
        }
    }

    private ScheduledExecutorService scheduler() {
        ScheduledExecutorService existing = streamScheduler.get();
        if (existing != null) return existing;
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "managame-ai-timeout");
            t.setDaemon(true);
            return t;
        });
        if (streamScheduler.compareAndSet(null, created)) {
            if (closed) created.shutdownNow();
            return created;
        }
        created.shutdownNow();
        return streamScheduler.get();
    }

    private <T> AiCall failImmediately(int code, String message, AiCallback<T> callback) {
        metrics.failed(code);
        AiResult<T> result = AiResult.fail(code, message);
        callbackExecutor.execute(() -> deliver(callback, result));
        return CompletedCall.INSTANCE;
    }

    private AiCall failStreamImmediately(int code, String message, AiStreamListener listener) {
        metrics.failed(code);
        AiResult<Void> result = AiResult.fail(code, message);
        callbackExecutor.execute(() -> deliverComplete(listener, result));
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

    private static String truncate(String body, int status) {
        if (body == null || body.isBlank()) return "HTTP " + status;
        return body.length() <= MAX_ERROR_BODY_CHARS ? body : body.substring(0, MAX_ERROR_BODY_CHARS);
    }

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

        InFlightCall(long startNanos) { this.startNanos = startNanos; }

        void attach(CompletableFuture<?> future) {
            httpFuture.set(future);
            if (done.get()) future.cancel(true);
        }

        boolean markDone() {
            if (!done.compareAndSet(false, true)) return false;
            active.remove(this);
            inFlight.decrementAndGet();
            metrics.recordLatency(startNanos);
            return true;
        }

        void cancelTransport() {
            CompletableFuture<?> f = httpFuture.get();
            if (f != null) f.cancel(true);
        }

        abstract boolean abort(int code, String message);

        @Override public boolean isDone() { return done.get(); }

        @Override public boolean cancel() { return abort(AI_CANCELLED, "Cancelled"); }
    }

    private final class UnaryCall<T> extends InFlightCall {
        private final AiCallback<T> callback;

        UnaryCall(long startNanos, AiCallback<T> callback) {
            super(startNanos);
            this.callback = callback;
        }

        void finish(AiResult<T> result) {
            if (!markDone()) return;
            if (!result.isSuccess()) metrics.failed(result.code());
            deliver(callback, result);
        }

        @Override boolean abort(int code, String message) {
            if (!markDone()) return false;
            cancelTransport();
            metrics.failed(code);
            AiResult<T> result = AiResult.fail(code, message);
            callbackExecutor.execute(() -> deliver(callback, result));
            return true;
        }
    }

    private final class StreamCall extends InFlightCall {
        private final String path;
        private final AiStreamListener listener;
        private final Object deliveryLock = new Object();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> idleTimer = new AtomicReference<>();
        private volatile long lastActivityNanos;

        StreamCall(long startNanos, String path, AiStreamListener listener) {
            super(startNanos);
            this.path = path;
            this.listener = listener;
        }

        void attachTimer(ScheduledFuture<?> future) {
            timer.set(future);
            if (isDone()) future.cancel(false);
        }

        void startIdleTimer(long idleNanos, Duration idleDuration) {
            lastActivityNanos = System.nanoTime();
            scheduleIdleCheck(idleNanos, idleNanos, idleDuration);
        }

        private void scheduleIdleCheck(long delayNanos, long idleNanos, Duration idleDuration) {
            if (isDone() || closed) return;
            ScheduledFuture<?> next;
            try {
                next = scheduler().schedule(() -> {
                    if (isDone()) return;
                    long remaining = idleNanos - (System.nanoTime() - lastActivityNanos);
                    if (remaining <= 0) {
                        if (abort(AI_TIMEOUT, "AI stream idle timed out after " + idleDuration)) {
                            LOG.log(Level.WARNING, () -> "AI stream idle timed out: " + path);
                        }
                    } else {
                        scheduleIdleCheck(remaining, idleNanos, idleDuration);
                    }
                }, delayNanos, TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException closedRace) {
                if (closed) abort(AI_CLOSED, "AiClient closed");
                return;
            }
            ScheduledFuture<?> previous = idleTimer.getAndSet(next);
            if (previous != null) previous.cancel(false);
            if (isDone()) next.cancel(false);
        }

        void touch() { lastActivityNanos = System.nanoTime(); }

        void attachSubscription(Flow.Subscription sub) {
            subscription.set(sub);
            if (isDone()) sub.cancel();
        }

        void emit(AiStreamEvent event) {
            synchronized (deliveryLock) {
                if (isDone()) return;
                metrics.streamEvent();
                try {
                    listener.onEvent(event);
                } catch (Throwable cause) {
                    LOG.log(Level.ERROR, "Uncaught exception in AiStreamListener.onEvent", cause);
                }
            }
        }

        void finish(AiResult<Void> result) {
            if (!markDone()) return;
            cancelTimers();
            if (!result.isSuccess()) metrics.failed(result.code());
            synchronized (deliveryLock) {
                deliverComplete(listener, result);
            }
        }

        @Override boolean abort(int code, String message) {
            if (!markDone()) return false;
            cancelTimers();
            Flow.Subscription sub = subscription.get();
            if (sub != null) sub.cancel();
            cancelTransport();
            metrics.failed(code);
            AiResult<Void> result = AiResult.fail(code, message);
            callbackExecutor.execute(() -> {
                synchronized (deliveryLock) {
                    deliverComplete(listener, result);
                }
            });
            return true;
        }

        private void cancelTimers() {
            ScheduledFuture<?> t = timer.getAndSet(null);
            if (t != null) t.cancel(false);
            ScheduledFuture<?> idle = idleTimer.getAndSet(null);
            if (idle != null) idle.cancel(false);
        }
    }

    private final class NotifyCall extends InFlightCall {
        NotifyCall(long startNanos) { super(startNanos); }

        @Override boolean abort(int code, String message) {
            if (!markDone()) return false;
            cancelTransport();
            metrics.failed(code);
            return true;
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
            this.parser = new SseParser(call::emit, maxEventChars);
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
                call.abort(AI_PROTOCOL_ERROR, oversized.getMessage());
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

    private static final class BoundedStringBodySubscriber implements HttpResponse.BodySubscriber<String> {
        private final int maxBytes;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int totalBytes;

        BoundedStringBodySubscriber(int maxBytes) { this.maxBytes = maxBytes; }

        @Override public java.util.concurrent.CompletionStage<String> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buf : buffers) {
                int remaining = buf.remaining();
                if (totalBytes + remaining > maxBytes) {
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLargeException(
                            "AI response body exceeded maxResponseBytes (" + maxBytes + ")"));
                    return;
                }
                totalBytes += remaining;
                if (buf.hasArray()) {
                    out.write(buf.array(), buf.arrayOffset() + buf.position(), remaining);
                    buf.position(buf.limit());
                } else {
                    byte[] tmp = new byte[remaining];
                    buf.get(tmp);
                    out.write(tmp, 0, remaining);
                }
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable throwable) { body.completeExceptionally(throwable); }

        @Override public void onComplete() { body.complete(out.toString(StandardCharsets.UTF_8)); }
    }

    public static final class Builder {
        private final URI baseUri;
        private AiCodec codec = AiCodec.basic();
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration streamIdleTimeout;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private int maxInFlight = DEFAULT_MAX_IN_FLIGHT;
        private int maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
        private int maxStreamEventChars = SseParser.DEFAULT_MAX_EVENT_CHARS;
        private final Map<String, String> headers = new LinkedHashMap<>();
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

        /** Default total timeout per call or stream; 30 seconds by default. */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
            this.timeout = timeout;
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

        /** Maximum concurrent in-flight calls, streams and notifies before failing with AI_OVERLOADED; 256 by default. */
        public Builder maxInFlight(int maxInFlight) {
            if (maxInFlight <= 0) throw new IllegalArgumentException("maxInFlight must be positive");
            this.maxInFlight = maxInFlight;
            return this;
        }

        /** Maximum response body size in bytes for unary calls before failing with AI_PROTOCOL_ERROR; 1 MiB by default. */
        public Builder maxResponseBytes(int maxResponseBytes) {
            if (maxResponseBytes <= 0) throw new IllegalArgumentException("maxResponseBytes must be positive");
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        /** Maximum single SSE line or event payload size in chars before aborting with AI_PROTOCOL_ERROR; 256 KiB by default. */
        public Builder maxStreamEventChars(int maxStreamEventChars) {
            if (maxStreamEventChars <= 0) throw new IllegalArgumentException("maxStreamEventChars must be positive");
            this.maxStreamEventChars = maxStreamEventChars;
            return this;
        }

        /** Default HTTP header sent on every request (for example {@code Authorization}). */
        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (name.isBlank()) throw new IllegalArgumentException("Header name must not be blank");
            this.headers.put(name, value);
            return this;
        }

        /** Propagates {@code key} from {@link AiRequest#metadata()} as HTTP header {@code headerName} when present. */
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
