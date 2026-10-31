package cn.managame.runtime.http;

import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;

import cn.managame.network.http.HttpExchange;
import cn.managame.network.http.HttpHandler;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeException;
import cn.managame.runtime.Route;
import cn.managame.runtime.annotation.AnnotatedMethod;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.lang.System.Logger.Level;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs HTTP requests on their {@link HttpMethod} endpoints as game tasks. It is the HttpHandler of an HTTP
 * listener; endpoints are registered once by passing the objects that declare them:
 *
 * <pre>{@code
 * @Domain(PLAYER)
 * class PlayerAdmin {
 *     @HttpMethod("/mail") String mail(SendMail mail) { ... }
 * }
 * HttpRoutes routes = HttpRoutes.builder(runtime)
 *         .source(request -> GameContext.of(0, 0, ROLE, HttpRequests.longQuery(request, "roleId")))
 *         .codec(json)
 *         .register(playerAdmin)
 *         .build();
 * NetworkServer.start(8080, HttpInitializer.http().contextPath("/admin").handler(routes).build());
 * }</pre>
 *
 * The codec reads an endpoint's argument from the request, normally its body. The request then runs in the
 * endpoint's domain, on the route key the domain's resolver finds for the argument and the source the
 * application builds from the request. Unknown paths are answered with 404, other methods with 405, an argument
 * the codec cannot read with 400, and so is a source or route that cannot be found because of
 * IllegalArgumentException, such as a missing parameter. The request stays readable until the endpoint returns. A
 * request the runtime refuses is answered with the codec's failure response.
 */
public final class HttpRoutes implements HttpHandler {
    private static final System.Logger LOG = System.getLogger("cn.managame.runtime.http");
    private static final GameContext NO_SOURCE = GameContext.of(0, 0);
    private final GameRuntime runtime;
    private final Map<String, Map<String, Endpoint>> endpoints;
    private final Map<String, String> allowedMethods;
    private final Function<? super FullHttpRequest, ? extends Context> source;
    private final HttpCodec codec;

    private HttpRoutes(Builder b) {
        runtime = b.runtime;
        Map<String, Map<String, Endpoint>> copy = new HashMap<>();
        b.endpoints.forEach((path, methods) -> copy.put(path, Map.copyOf(methods)));
        endpoints = Map.copyOf(copy);
        allowedMethods = b.endpoints.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                entry -> String.join(", ", entry.getValue().keySet())));
        source = b.source;
        codec = b.codec;
    }

    public static Builder builder(GameRuntime runtime) { return new Builder(runtime); }

    @Override public void handle(HttpExchange exchange) {
        FullHttpRequest request = exchange.request();
        Map<String, Endpoint> methods = endpoints.get(exchange.path());
        if (methods == null) {
            exchange.respond(HttpResponseStatus.NOT_FOUND);
            return;
        }
        Endpoint endpoint = methods.get(request.method().name());
        if (endpoint == null) {
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.METHOD_NOT_ALLOWED);
            response.headers().set(HttpHeaderNames.ALLOW, allowedMethods.get(exchange.path()));
            exchange.respond(response);
            return;
        }
        Object argument;
        try {
            argument = Objects.requireNonNull(codec.decode(request, endpoint.argument()), "The codec read nothing");
        } catch (Exception unreadable) {
            badRequest(exchange, unreadable);
            return;
        }
        Context from;
        Route route;
        try {
            from = Objects.requireNonNull(source.apply(request), "The source function returned null");
            route = runtime.route(endpoint.method().domain(), from, argument);
        } catch (IllegalArgumentException invalid) {
            badRequest(exchange, invalid);
            return;
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Cannot route HTTP request " + request.uri(), e);
            exchange.respond(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            return;
        }
        HttpRequestContext context = new HttpRequestContext(route, from.metadata(), exchange, codec);
        request.retain(); // the exchange releases its reference when this returns; the task keeps its own
        try {
            runtime.execute(context, task -> {
                try {
                    Object result = endpoint.method().invoke(task, argument);
                    if (result != null) task.reply(result);
                } finally {
                    request.release();
                }
            });
        } catch (GameRuntimeException refused) {
            request.release();
            context.fail(refused.errorCode());
        } catch (RuntimeException e) {
            request.release();
            LOG.log(Level.ERROR, "Cannot run HTTP request " + request.uri(), e);
            context.fail(RUNTIME_EXECUTION_ERROR);
        }
    }

    private static void badRequest(HttpExchange exchange, Exception cause) {
        if (LOG.isLoggable(Level.DEBUG)) LOG.log(Level.DEBUG, "Bad HTTP request " + exchange.request().uri() + ": " + cause);
        exchange.respond(HttpResponseStatus.BAD_REQUEST);
    }

    /** An endpoint and the type of the argument it takes. */
    private record Endpoint(AnnotatedMethod method, Type argument) {}

    public static final class Builder {
        private final GameRuntime runtime;
        private final Map<String, Map<String, Endpoint>> endpoints = new HashMap<>();
        private Function<? super FullHttpRequest, ? extends Context> source = request -> NO_SOURCE;
        private HttpCodec codec = HttpCodec.basic();

        private Builder(GameRuntime runtime) { this.runtime = Objects.requireNonNull(runtime, "runtime"); }

        /**
         * Registers every {@link HttpMethod} of each object. Two endpoints for one method and path, a path not
         * starting with a slash, or a context other than HttpRequestContext or its supertypes throw
         * IllegalArgumentException.
         */
        public Builder register(Object... targets) {
            for (Object target : targets) {
                for (AnnotatedMethod method : AnnotatedMethod.scan(target, HttpMethod.class)) {
                    method.requireContext(HttpRequestContext.class);
                    HttpMethod mapping = method.method().getAnnotation(HttpMethod.class);
                    String path = mapping.value(), verb = mapping.method().toUpperCase(Locale.ROOT);
                    if (!path.startsWith("/")) throw new IllegalArgumentException(method + ": path must start with /");
                    Type[] parameters = method.method().getGenericParameterTypes();
                    Endpoint endpoint = new Endpoint(method, parameters[parameters.length - 1]);
                    Endpoint existing = endpoints.computeIfAbsent(path, p -> new LinkedHashMap<>()).putIfAbsent(verb, endpoint);
                    if (existing != null)
                        throw new IllegalArgumentException(verb + " " + path + " is handled by both " + existing.method() + " and " + method);
                }
            }
            return this;
        }

        /**
         * The source context of a request, whose business identity and metadata the endpoint acts with and from
         * which the domain's resolver may take the route key; an empty context by default. It runs on the
         * connection's EventLoop and must not block; IllegalArgumentException answers 400.
         */
        public Builder source(Function<? super FullHttpRequest, ? extends Context> source) {
            this.source = Objects.requireNonNull(source, "source");
            return this;
        }

        /** How arguments are read and results and failures written; {@link HttpCodec#basic()} by default. */
        public Builder codec(HttpCodec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /** With the basic codec, an endpoint taking an argument it cannot read throws IllegalArgumentException. */
        public HttpRoutes build() {
            if (codec == BasicHttpCodec.INSTANCE)
                for (Map<String, Endpoint> methods : endpoints.values())
                    for (Endpoint endpoint : methods.values())
                        if (!BasicHttpCodec.decodes(endpoint.argument()))
                            throw new IllegalArgumentException(endpoint.method() + " takes " + endpoint.argument().getTypeName()
                                    + ", which the basic codec cannot read; configure an HttpCodec");
            return new HttpRoutes(this);
        }
    }
}
