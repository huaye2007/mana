package cn.managame.network.http;

import io.netty.handler.codec.http.FullHttpResponse;

/** One final response per request; safe to complete from any thread. */
public interface HttpResponseCallback {
    /**
     * Consumes one response reference on every call, including duplicate or late completion.
     * Returns true only for the first completion, not as confirmation of transmission or peer receipt.
     * After this call, the caller must not access the transferred response reference.
     */
    boolean onResponse(FullHttpResponse response);

    /** Claims failure once; attempts an empty 500 and closes. Disconnect does not undo business work. */
    boolean onFail(Throwable cause);
}
