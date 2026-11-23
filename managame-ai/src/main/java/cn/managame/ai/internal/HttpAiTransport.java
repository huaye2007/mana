package cn.managame.ai.internal;

import static cn.managame.FrameworkErrorCodes.AI_CANCELLED;
import static cn.managame.FrameworkErrorCodes.AI_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.AI_UNAVAILABLE;
import static cn.managame.FrameworkErrorCodes.SUCCESS;

import cn.managame.ai.AiCall;
import cn.managame.ai.AiChunk;
import cn.managame.ai.AiStreamListener;
import cn.managame.ai.spi.AiTransport;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Built-in {@link AiTransport} over JDK {@link HttpClient}, supporting asynchronous HTTP/JSON and SSE streaming.
 * Internal implementation, not public API.
 */
public final class HttpAiTransport implements AiTransport {
    private static final System.Logger LOG = System.getLogger("cn.managame.ai.client");
    private static final int MAX_ERROR_BODY_CHARS = 256;

    private final String baseUrl;
    private final HttpClient httpClient;
    private final boolean ownsClient;
    private final Executor callbackExecutor;
    private final HashedWheelTimer streamTimer;

    public HttpAiTransport(String baseUrl, HttpClient httpClient, boolean ownsClient) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.ownsClient = ownsClient;
        this.callbackExecutor = httpClient.executor().orElse(ForkJoinPool.commonPool());
        this.streamTimer = new HashedWheelTimer(r -> {
            Thread t = new Thread(r, "managame-ai-stream-timer");
            t.setDaemon(true);
            return t;
        }, 50, TimeUnit.MILLISECONDS);
    }

    @Override public AiCall send(Request request, Callback callback) {
        URI uri;
        try {
            uri = resolveUri(request.path());
        } catch (IllegalArgumentException badUri) {
            return failedUnary(callback, AI_UNAVAILABLE, "Invalid AI request URI: " + badUri.getMessage());
        }
        HttpRequest httpRequest = buildRequest(uri, request, false);
        UnaryCall call = new UnaryCall(callback);
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        call.attach(future);
        future.whenComplete((response, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                int code = mapError(cause);
                call.finish(code, null, describeError(cause));
                return;
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                call.finish(SUCCESS, response.body(), null);
            } else {
                call.finish(AI_UNAVAILABLE, null, "HTTP " + status + snippet(response.body()));
            }
        });
        return call;
    }

    @Override public AiCall stream(Request request, AiStreamListener listener) {
        URI uri;
        try {
            uri = resolveUri(request.path());
        } catch (IllegalArgumentException badUri) {
            return failedStream(listener, AI_UNAVAILABLE, "Invalid AI request URI: " + badUri.getMessage());
        }
        HttpRequest httpRequest = buildRequest(uri, request, true);
        StreamCall call = new StreamCall(listener);
        Timeout timeoutHandle = streamTimer.newTimeout(t -> call.timeout(),
                request.timeout().toNanos(), TimeUnit.NANOSECONDS);
        call.attachTimeout(timeoutHandle);

        BodyHandler<String> bodyHandler = info -> {
            int status = info.statusCode();
            if (status >= 200 && status < 300) {
                return BodySubscribers.mapping(
                        BodySubscribers.fromLineSubscriber(call.subscriber()),
                        ignored -> "");
            }
            return BodySubscribers.ofString(StandardCharsets.UTF_8);
        };

        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(httpRequest, bodyHandler);
        call.attachFuture(future);
        future.whenComplete((response, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                call.fail(mapError(cause), describeError(cause));
                return;
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                call.complete();
            } else {
                call.fail(AI_UNAVAILABLE, "HTTP " + status + snippet(response.body()));
            }
        });
        return call;
    }

    @Override public void close() {
        streamTimer.stop();
        if (ownsClient) {
            httpClient.close();
        }
    }

    private HttpRequest buildRequest(URI uri, Request request, boolean streaming) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(request.timeout());
        boolean hasContentType = false;
        boolean hasAccept = false;
        for (Map.Entry<String, String> entry : request.headers().entrySet()) {
            String name = entry.getKey();
            if (name.equalsIgnoreCase("Content-Type")) hasContentType = true;
            if (name.equalsIgnoreCase("Accept")) hasAccept = true;
            builder.header(name, entry.getValue());
        }
        if (!hasContentType && request.contentType() != null && request.body().length > 0) {
            builder.header("Content-Type", request.contentType());
        }
        if (streaming && !hasAccept) {
            builder.header("Accept", "text/event-stream");
        }
        HttpRequest.BodyPublisher publisher = request.body().length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body());
        builder.POST(publisher);
        return builder.build();
    }

    private URI resolveUri(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return URI.create(path);
        }
        if (baseUrl == null) {
            throw new IllegalArgumentException("Relative path requires a baseUrl: " + path);
        }
        return URI.create(path.startsWith("/") ? baseUrl + path : baseUrl + "/" + path);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return null;
        URI uri = URI.create(baseUrl);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("baseUrl must use http or https scheme: " + baseUrl);
        }
        String normalized = baseUrl;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private AiCall failedUnary(Callback callback, int code, String message) {
        callbackExecutor.execute(() -> callback.onComplete(code, null, message));
        return CompletedCall.FAILED;
    }

    private AiCall failedStream(AiStreamListener listener, int code, String message) {
        callbackExecutor.execute(() -> listener.onError(code, message));
        return CompletedCall.FAILED;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cur = error;
        while ((cur instanceof CompletionException || cur instanceof ExecutionException) && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static int mapError(Throwable cause) {
        if (cause instanceof HttpTimeoutException) return AI_TIMEOUT;
        if (cause instanceof CancellationException) return AI_CANCELLED;
        return AI_UNAVAILABLE;
    }

    private static String describeError(Throwable cause) {
        String msg = cause.getMessage();
        return msg == null || msg.isBlank() ? cause.getClass().getSimpleName() : msg;
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) return "";
        String trimmed = body.trim();
        if (trimmed.length() > MAX_ERROR_BODY_CHARS) trimmed = trimmed.substring(0, MAX_ERROR_BODY_CHARS) + "...";
        return ": " + trimmed;
    }

    private enum CompletedCall implements AiCall {
        FAILED;
        @Override public boolean cancel() { return false; }
        @Override public boolean isDone() { return true; }
        @Override public boolean isCancelled() { return false; }
    }

    private final class UnaryCall implements AiCall {
        private final Callback callback;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile boolean cancelled;
        private volatile CompletableFuture<HttpResponse<String>> future;

        UnaryCall(Callback callback) { this.callback = callback; }

        void attach(CompletableFuture<HttpResponse<String>> future) {
            this.future = future;
            if (cancelled) future.cancel(true);
        }

        void finish(int code, String body, String message) {
            if (!finished.compareAndSet(false, true)) return;
            callback.onComplete(code, body, message);
        }

        @Override public boolean cancel() {
            if (!finished.compareAndSet(false, true)) return false;
            cancelled = true;
            CompletableFuture<HttpResponse<String>> f = future;
            if (f != null) f.cancel(true);
            callbackExecutor.execute(() -> callback.onComplete(AI_CANCELLED, null, "Cancelled"));
            return true;
        }

        @Override public boolean isDone() { return finished.get(); }
        @Override public boolean isCancelled() { return cancelled; }
    }

    private final class StreamCall implements AiCall {
        private final AiStreamListener listener;
        private final SseParser parser = new SseParser();
        private final Object callbackLock = new Object();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile boolean cancelled;
        private volatile CompletableFuture<HttpResponse<String>> future;
        private volatile Flow.Subscription subscription;
        private volatile Timeout timeoutHandle;

        StreamCall(AiStreamListener listener) { this.listener = listener; }

        void attachTimeout(Timeout handle) {
            this.timeoutHandle = handle;
            if (finished.get()) handle.cancel();
        }

        void attachFuture(CompletableFuture<HttpResponse<String>> f) {
            this.future = f;
            if (finished.get()) f.cancel(true);
        }

        Flow.Subscriber<String> subscriber() {
            return new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription s) {
                    subscription = s;
                    if (finished.get()) s.cancel();
                    else s.request(Long.MAX_VALUE);
                }

                @Override public void onNext(String line) {
                    if (finished.get()) return;
                    parser.feedLine(line, StreamCall.this::emitChunk);
                }

                @Override public void onError(Throwable throwable) {
                    fail(mapError(unwrap(throwable)), describeError(unwrap(throwable)));
                }

                @Override public void onComplete() {
                    complete();
                }
            };
        }

        private void emitChunk(AiChunk chunk) {
            synchronized (callbackLock) {
                if (finished.get()) return;
                try {
                    listener.onChunk(chunk);
                } catch (Throwable t) {
                    LOG.log(Level.ERROR, "AI stream listener onChunk threw", t);
                }
            }
        }

        void complete() {
            if (!finished.compareAndSet(false, true)) return;
            cancelTimeout();
            synchronized (callbackLock) {
                parser.finish(chunk -> {
                    try {
                        listener.onChunk(chunk);
                    } catch (Throwable t) {
                        LOG.log(Level.ERROR, "AI stream listener onChunk threw", t);
                    }
                });
                try {
                    listener.onComplete();
                } catch (Throwable t) {
                    LOG.log(Level.ERROR, "AI stream listener onComplete threw", t);
                }
            }
        }

        void fail(int code, String message) {
            if (!finished.compareAndSet(false, true)) return;
            cancelResources();
            deliverError(code, message);
        }

        void timeout() {
            if (!finished.compareAndSet(false, true)) return;
            cancelResources();
            callbackExecutor.execute(() -> deliverError(AI_TIMEOUT, "AI stream timed out"));
        }

        @Override public boolean cancel() {
            if (!finished.compareAndSet(false, true)) return false;
            cancelled = true;
            cancelResources();
            callbackExecutor.execute(() -> deliverError(AI_CANCELLED, "Cancelled"));
            return true;
        }

        @Override public boolean isDone() { return finished.get(); }
        @Override public boolean isCancelled() { return cancelled; }

        private void deliverError(int code, String message) {
            synchronized (callbackLock) {
                try {
                    listener.onError(code, message);
                } catch (Throwable t) {
                    LOG.log(Level.ERROR, "AI stream listener onError threw", t);
                }
            }
        }

        private void cancelTimeout() {
            Timeout handle = timeoutHandle;
            if (handle != null) handle.cancel();
        }

        private void cancelResources() {
            cancelTimeout();
            Flow.Subscription s = subscription;
            if (s != null) s.cancel();
            CompletableFuture<HttpResponse<String>> f = future;
            if (f != null) f.cancel(true);
        }
    }
}
