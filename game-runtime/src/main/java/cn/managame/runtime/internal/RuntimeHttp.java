package cn.managame.runtime.internal;

import cn.managame.network.http.HttpResponseCallback;
import cn.managame.runtime.context.Context;
import cn.managame.runtime.context.DefaultContext;
import cn.managame.runtime.http.HttpContext;
import cn.managame.runtime.http.DefaultHttpContext;
import cn.managame.runtime.http.HttpContextFactory;
import cn.managame.runtime.http.HttpDispatcher;
import cn.managame.runtime.http.HttpResultCallback;
import cn.managame.runtime.http.HttpResultCodec;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import static cn.managame.core.FrameworkErrorCodes.*;
import static cn.managame.runtime.internal.RuntimeCompiler.*;

/** Frozen HTTP lookup and invocation adapter; uses the runtime's existing scoped Route submission. */
final class RuntimeHttp implements HttpDispatcher {
    private final Map<HttpEndpoint, HttpBinding> handlers;
    private final Map<String, String> allowedMethods;
    private final HttpContextFactory contexts;
    private final HttpResultCodec results;
    private final String contentType;
    private final BooleanSupplier closed;
    private final BiFunction<Context, Runnable, Integer> submit;
    private final BiConsumer<Context, Throwable> report;

    RuntimeHttp(Map<HttpEndpoint, HttpBinding> handlers, HttpContextFactory contexts, HttpResultCodec results, BooleanSupplier closed,
                BiFunction<Context, Runnable, Integer> submit, BiConsumer<Context, Throwable> report) {
        this.handlers = handlers;
        this.contexts = contexts;
        this.results = results;
        contentType = Objects.requireNonNull(results.contentType());
        if (contentType.isBlank()) throw new IllegalArgumentException("HTTP result content type is blank");
        new DefaultHttpHeaders().set(HttpHeaderNames.CONTENT_TYPE, contentType); // Validate before any runtime resources start.
        this.closed = closed;
        this.submit = submit;
        this.report = report;
        Map<String, SortedSet<String>> methods = new HashMap<>();
        handlers.keySet().forEach(key -> methods.computeIfAbsent(key.path(), _ -> new TreeSet<>()).add(key.method()));
        Map<String, String> allowed = new HashMap<>();
        methods.forEach((path, verbs) -> allowed.put(path, String.join(", ", verbs)));
        allowedMethods = Map.copyOf(allowed);
    }

    @Override public void dispatch(FullHttpRequest request, HttpResponseCallback callback) {
        Objects.requireNonNull(request);
        var completion = new ResponseOnce(Objects.requireNonNull(callback), results, contentType, report);
        if (closed.getAsBoolean()) { reject(completion, HttpResponseStatus.SERVICE_UNAVAILABLE, null); return; }
        String uri = request.uri();
        if (!uri.startsWith("/") || uri.indexOf('#') >= 0) { reject(completion, HttpResponseStatus.BAD_REQUEST, null); return; }
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        HttpBinding binding = handlers.get(new HttpEndpoint(request.method().name(), path));
        if (binding == null) {
            String allowed = allowedMethods.get(path);
            reject(completion, allowed == null ? HttpResponseStatus.NOT_FOUND : HttpResponseStatus.METHOD_NOT_ALLOWED, allowed);
            return;
        }
        HttpContext context;
        Object payload;
        try {
            completion.context = new DefaultContext(binding.domain(), 0);
            long key = binding.routeKey() == null ? 0 : binding.routeKey().extract(request);
            if (binding.routeKey() != null && key == 0) { reject(completion, HttpResponseStatus.BAD_REQUEST, null); return; }
            context = contexts == null ? new DefaultHttpContext(binding.domain(), key, request, completion)
                    : contexts.create(binding.domain(), key, request, completion);
            if (completion.done()) return; // Factory may reject without dispatching a business method.
            if (context == null || context.routeDomain() != binding.domain() || context.request() != request
                    || context.responseCallback() != completion
                    || (binding.routeKey() != null && context.routeKey() != key)
                    || (binding.contextType() != null && !binding.contextType().isInstance(context)))
                throw new IllegalStateException("HTTP context factory returned an incompatible context");
            if (context.routeKey() == 0) { reject(completion, HttpResponseStatus.BAD_REQUEST, null); return; }
            completion.context = context;
            payload = binding.decoder().apply(request);
        } catch (IllegalArgumentException invalidInput) {
            reject(completion, HttpResponseStatus.BAD_REQUEST, null); return;
        } catch (Throwable cause) {
            report.accept(new DefaultContext(binding.domain(), 0), cause);
            completion.onFail(cause); return;
        }

        // Network releases its borrowed reference after this dispatch call; hold one through Route execution.
        request.retain();
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) request.release(); };
        try {
            int error = submit.apply(context, () -> {
                try {
                    Object result = binding.invoke(context, payload);
                    if (binding.returnsResult()) completion.onResponse(result);
                } catch (Throwable cause) {
                    report.accept(context, cause);
                    completion.onFail(cause);
                } finally { release.run(); }
            });
            if (error != 0) {
                release.run();
                reject(completion, error == INVALID_ROUTE_KEY ? HttpResponseStatus.BAD_REQUEST : HttpResponseStatus.SERVICE_UNAVAILABLE, null);
            }
        } catch (Throwable cause) {
            release.run();
            report.accept(context, cause);
            completion.onFail(cause);
        }
    }

    private static void reject(ResponseOnce callback, HttpResponseStatus status, String allowed) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        HttpUtil.setContentLength(response, 0);
        if (allowed != null) response.headers().set(HttpHeaderNames.ALLOW, allowed);
        callback.transportResponse(response);
    }

    private static final class ResponseOnce implements HttpResultCallback {
        private final HttpResponseCallback delegate;
        private final HttpResultCodec codec;
        private final String contentType;
        private final BiConsumer<Context, Throwable> report;
        private volatile Context context = new DefaultContext(0, 0);
        private final AtomicBoolean completed = new AtomicBoolean();
        ResponseOnce(HttpResponseCallback delegate, HttpResultCodec codec, String contentType, BiConsumer<Context, Throwable> report) {
            this.delegate = delegate; this.codec = codec; this.contentType = contentType; this.report = report;
        }
        boolean done() { return completed.get(); }
        boolean transportResponse(FullHttpResponse response) {
            if (!completed.compareAndSet(false, true)) { response.release(); return false; }
            return delegate.onResponse(response);
        }
        public boolean onResponse(int statusCode, Object result) {
            if (statusCode < 200 || statusCode > 599) throw new IllegalArgumentException("HTTP result status must be 200..599");
            if (!completed.compareAndSet(false, true)) return false;
            FullHttpResponse response;
            try {
                if (result instanceof HttpObject || result instanceof io.netty.util.ReferenceCounted)
                    throw new IllegalArgumentException("HTTP business results must not be transport objects");
                byte[] bytes = Objects.requireNonNull(codec.encode(result), "HTTP result codec returned null");
                response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(statusCode), Unpooled.wrappedBuffer(bytes));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
                HttpUtil.setContentLength(response, bytes.length);
            } catch (Throwable cause) {
                report.accept(context, cause);
                return delegate.onFail(cause);
            }
            return delegate.onResponse(response);
        }
        public boolean onFail(Throwable cause) {
            Objects.requireNonNull(cause);
            if (!completed.compareAndSet(false, true)) return false;
            return delegate.onFail(cause);
        }
    }
}
