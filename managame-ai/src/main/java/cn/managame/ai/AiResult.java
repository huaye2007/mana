package cn.managame.ai;

import cn.managame.FrameworkErrorCodes;
import java.util.Objects;

/**
 * Outcome of an {@link AiClient} call or stream. {@code code} is {@link FrameworkErrorCodes#SUCCESS} on a 2xx
 * response that decoded cleanly, or one of the {@code AI_*} framework error codes ({@code 5001..5006}). {@code status}
 * is the HTTP status code, or {@code 0} when the call failed before an HTTP response arrived.
 */
public record AiResult<T>(int code, int status, T data, String message) {
    public AiResult {
        Objects.requireNonNull(message, "message");
    }

    public boolean isSuccess() { return code == FrameworkErrorCodes.SUCCESS; }

    public static <T> AiResult<T> ok(int status, T data) {
        return new AiResult<>(FrameworkErrorCodes.SUCCESS, status, data, "");
    }

    public static <T> AiResult<T> fail(int code, int status, String message) {
        if (code == FrameworkErrorCodes.SUCCESS) throw new IllegalArgumentException("Failure code must not be SUCCESS");
        return new AiResult<>(code, status, null, message == null ? "" : message);
    }

    public static <T> AiResult<T> fail(int code, String message) {
        return fail(code, 0, message);
    }
}
