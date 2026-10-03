package cn.managame.spring.runtime;

import cn.managame.network.http.HttpResponseCallback;
import cn.managame.runtime.http.HttpDispatcher;
import io.netty.handler.codec.http.*;

/** Raw, segment-boundary prefix mapping; a retained duplicate keeps the transport request untouched. */
final class HttpContextPath implements HttpDispatcher {
    private final String prefix;
    private final HttpDispatcher delegate;

    HttpContextPath(String value, HttpDispatcher delegate) {
        if (value.isEmpty() || value.equals("/")) prefix = "";
        else {
            String path = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
            if (!path.startsWith("/") || !path.matches("/[A-Za-z0-9._~/-]+") || path.contains("//") || path.endsWith("/"))
                throw new IllegalArgumentException("Invalid game.http.context-path: " + value);
            for (String segment : path.substring(1).split("/"))
                if (segment.equals(".") || segment.equals(".."))
                    throw new IllegalArgumentException("Invalid game.http.context-path: " + value);
            prefix = path;
        }
        this.delegate = delegate;
    }

    @Override public void dispatch(FullHttpRequest request, HttpResponseCallback callback) {
        if (prefix.isEmpty()) { delegate.dispatch(request, callback); return; }
        String uri = request.uri();
        if (!uri.startsWith("/") || uri.indexOf('#') >= 0) {
            reject(callback, HttpResponseStatus.BAD_REQUEST); return;
        }
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        if (!path.equals(prefix) && !path.startsWith(prefix + "/")) {
            reject(callback, HttpResponseStatus.NOT_FOUND); return;
        }
        String relative = path.substring(prefix.length());
        if (relative.isEmpty()) relative = "/";
        FullHttpRequest mapped = request.retainedDuplicate();
        try {
            mapped.setUri(relative + (query < 0 ? "" : uri.substring(query)));
            delegate.dispatch(mapped, callback);
        } finally { mapped.release(); }
    }

    private static void reject(HttpResponseCallback callback, HttpResponseStatus status) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        HttpUtil.setContentLength(response, 0);
        callback.onResponse(response);
    }
}
