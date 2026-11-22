package cn.managame.ai;

import cn.managame.FrameworkErrorCodes;

/**
 * An AI request failed or was refused; {@link #errorCode} is one of {@link FrameworkErrorCodes} ({@code 5001..5999}).
 * Refusals and timeouts carry no stack trace, since they happen under load and their code and message say why.
 */
public class AiException extends RuntimeException {
    private final int errorCode;

    public AiException(int errorCode, String message) {
        super(FrameworkErrorCodes.describe(errorCode) + ": " + message, null, true, false);
        this.errorCode = errorCode;
    }

    public AiException(int errorCode, String message, Throwable cause) {
        super(FrameworkErrorCodes.describe(errorCode) + ": " + message, cause);
        this.errorCode = errorCode;
    }

    public int errorCode() { return errorCode; }
}
