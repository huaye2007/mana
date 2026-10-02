package cn.managame.runtime.http;

import cn.managame.runtime.context.DefaultContext;
import io.netty.handler.codec.http.FullHttpRequest;
import java.util.Objects;

/** Immutable Route fields; may be extended with application authentication/session data. */
public class DefaultHttpContext extends DefaultContext implements HttpContext {
    private final FullHttpRequest request;
    private final HttpResultCallback callback;

    public DefaultHttpContext(int domain, long key, FullHttpRequest request, HttpResultCallback callback) {
        super(domain, key);
        this.request = Objects.requireNonNull(request);
        this.callback = Objects.requireNonNull(callback);
    }

    public FullHttpRequest request() { return request; }
    public HttpResultCallback responseCallback() { return callback; }
}
