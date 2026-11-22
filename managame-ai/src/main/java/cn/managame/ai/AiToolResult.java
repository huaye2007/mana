package cn.managame.ai;

import cn.managame.FrameworkErrorCodes;
import java.util.Objects;

/**
 * Optional structured envelope for {@link AiTools} responses that distinguishes a normal result, a business/argument
 * rejection (which an external AI can explain to the player or correct), and a transient system failure.
 */
public record AiToolResult(boolean ok, int code, Object data, String message, boolean retryable) {

    /** Successful tool execution with an optional structured payload. */
    public static AiToolResult ok(Object data) {
        return new AiToolResult(true, FrameworkErrorCodes.SUCCESS, data, null, false);
    }

    /** Business rule or argument rejection (non-retryable with the same input, e.g. insufficient coins or unknown role). */
    public static AiToolResult reject(int code, String message) {
        if (code <= 0) throw new IllegalArgumentException("Failure code must be positive");
        return new AiToolResult(false, code, null, Objects.requireNonNull(message, "message"), false);
    }

    /** Transient or system failure (external agent may retry later or surface an error). */
    public static AiToolResult fail(int code, String message, boolean retryable) {
        if (code <= 0) throw new IllegalArgumentException("Failure code must be positive");
        return new AiToolResult(false, code, null, Objects.requireNonNull(message, "message"), retryable);
    }
}
