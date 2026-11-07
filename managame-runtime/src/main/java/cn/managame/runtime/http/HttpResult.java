package cn.managame.runtime.http;

import cn.managame.FrameworkErrorCodes;
import java.util.Objects;

/**
 * An answer in the common shape of an API, for the {@link HttpMethod} endpoints that want one: {@code code} is
 * {@link FrameworkErrorCodes#SUCCESS} or a failure code, {@code data} the result, and {@code msg}, never null, the text
 * for whoever called. HTTP takes a text where a game packet takes args, since its callers keep no table of messages.
 *
 * <p>It is a result like any other: it goes through the routes' {@link HttpCodec}, so a JSON codec writes it as
 * {@code {code, data, msg}} with no work of its own. An endpoint whose format is fixed by the other side, such as a
 * payment callback, returns its own type instead.
 */
public record HttpResult(int code, Object data, String msg) {
    public HttpResult { Objects.requireNonNull(msg, "msg"); }

    /** A success with {@code data}, null when there is nothing to send back, and an empty msg. */
    public static HttpResult ok(Object data) { return new HttpResult(FrameworkErrorCodes.SUCCESS, data, ""); }

    /** A failure with {@code code}, one of the FrameworkErrorCodes or an application code, and its {@code msg}. */
    public static HttpResult fail(int code, String msg) { return new HttpResult(code, null, msg); }
}
