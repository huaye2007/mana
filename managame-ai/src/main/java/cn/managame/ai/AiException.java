package cn.managame.ai;

import cn.managame.FrameworkErrorCodes;

/** Raised when an AI operation fails synchronously with a framework error code. */
public final class AiException extends RuntimeException {
    private final int errorCode;

    public AiException(int errorCode, String message) {
        super(message != null ? message : FrameworkErrorCodes.describe(errorCode));
        this.errorCode = errorCode;
    }

    public AiException(int errorCode, String message, Throwable cause) {
        super(message != null ? message : FrameworkErrorCodes.describe(errorCode), cause);
        this.errorCode = errorCode;
    }

    public int errorCode() { return errorCode; }
}
