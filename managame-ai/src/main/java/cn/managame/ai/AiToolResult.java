package cn.managame.ai;

import cn.managame.FrameworkErrorCodes;

/**
 * Outcome of invoking an AI tool. {@code code == 0} ({@link FrameworkErrorCodes#SUCCESS}) indicates success with
 * {@code data}; a positive code is either a framework code from {@link FrameworkErrorCodes} or an application business
 * failure code returned cleanly to the AI caller.
 */
public record AiToolResult(int code, Object data, String message) {
    public boolean isSuccess() { return code == FrameworkErrorCodes.SUCCESS; }

    public static AiToolResult ok(Object data) {
        return new AiToolResult(FrameworkErrorCodes.SUCCESS, data, null);
    }

    public static AiToolResult fail(int code, String message) {
        if (code <= 0) throw new IllegalArgumentException("Failure code must be positive: " + code);
        return new AiToolResult(code, null, message);
    }
}
