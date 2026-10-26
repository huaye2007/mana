package cn.managame.runtime.http;

import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;

import cn.managame.Metadata;
import cn.managame.network.http.HttpExchange;
import cn.managame.runtime.Context;
import cn.managame.runtime.RequestContext;
import cn.managame.runtime.Route;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import java.lang.System.Logger.Level;
import java.net.SocketAddress;

/**
 * The context of an HTTP request: answers go out as HTTP responses through the routes' codec. An endpoint rarely
 * needs it; when it does, it takes it as its first parameter or reads {@link #current()}.
 */
public final class HttpRequestContext implements RequestContext {
    private static final System.Logger LOG = System.getLogger("cn.managame.runtime.http");
    private final Route route;
    private final Metadata metadata;
    private final HttpExchange exchange;
    private final HttpCodec codec;

    HttpRequestContext(Route route, Metadata metadata, HttpExchange exchange, HttpCodec codec) {
        this.route = route;
        this.metadata = metadata;
        this.exchange = exchange;
        this.codec = codec;
    }

    /** The context of the HTTP endpoint running on this thread; IllegalStateException in any other task or outside one. */
    public static HttpRequestContext current() {
        if (Context.current() instanceof HttpRequestContext context) return context;
        throw new IllegalStateException("The running task is not an HTTP request");
    }

    @Override public int domain() { return route.domain(); }
    @Override public long routingKey() { return route.routingKey(); }
    @Override public byte businessType() { return route.businessType(); }
    @Override public long businessId() { return route.businessId(); }
    @Override public Metadata metadata() { return metadata; }

    /** The request, for its headers or query; readable until the endpoint returns. */
    public FullHttpRequest request() { return exchange.request(); }

    /** The requesting peer, for example to restrict an endpoint to known addresses. */
    public SocketAddress remoteAddress() { return exchange.remoteAddress(); }

    /** A result the codec cannot encode is answered with the failure for RUNTIME_EXECUTION_ERROR, and logged. */
    @Override public boolean reply(Object result) {
        FullHttpResponse response;
        try {
            response = codec.encode(result);
        } catch (Throwable cause) {
            LOG.log(Level.ERROR, "Cannot encode the HTTP result " + result.getClass().getName(), cause);
            return fail(RUNTIME_EXECUTION_ERROR);
        }
        return exchange.respond(response);
    }

    @Override public boolean fail(int errorCode) { return exchange.respond(codec.encodeFailure(errorCode)); }
}
