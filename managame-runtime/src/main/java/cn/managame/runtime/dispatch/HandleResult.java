package cn.managame.runtime.dispatch;

import cn.managame.FrameworkErrorCodes;
import java.util.Objects;

/**
 * The answer to a dispatched message: what a {@link cn.managame.runtime.annotation.HandleMethod} returns, and what the
 * application's answer callback receives, whichever way the message was answered, the framework's own failures
 * included. {@code code} is {@link FrameworkErrorCodes#SUCCESS} or a failure code, {@code data} the result, and
 * {@code args}, never null, the values the code's message shows, which the application places in its packet format.
 */
public record HandleResult(int code, Object data, String[] args) {
    static final String[] NO_ARGS = {};

    public HandleResult { Objects.requireNonNull(args, "args"); }

    /** A success with {@code data}, null when there is nothing to send back. */
    public static HandleResult ok(Object data) { return new HandleResult(FrameworkErrorCodes.SUCCESS, data, NO_ARGS); }

    /** A failure with {@code code}, one of the FrameworkErrorCodes or an application code, and its {@code args}. */
    public static HandleResult fail(int code, String... args) { return new HandleResult(code, null, args); }
}
