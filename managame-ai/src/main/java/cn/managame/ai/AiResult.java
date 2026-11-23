package cn.managame.ai;

import cn.managame.FrameworkErrorCodes;

/**
 * Outcome of an outbound {@link AiClient} call. {@code code == 0} ({@link FrameworkErrorCodes#SUCCESS}) means
 * success with {@code data}; a positive code is a failure from {@link FrameworkErrorCodes} with a diagnostic
 * {@code message}.
 */
public record AiResult<T>(int code, T data, String message) {
    public boolean isSuccess() { return code == FrameworkErrorCodes.SUCCESS; }

    public static <T> AiResult<T> ok(T data) {
        return new AiResult<>(FrameworkErrorCodes.SUCCESS, data, null);
    }

    public static <T> AiResult<T> fail(int code, String message) {
        if (code <= 0) throw new IllegalArgumentException("Failure code must be positive: " + code);
        return new AiResult<>(code, null, message);
    }
}
