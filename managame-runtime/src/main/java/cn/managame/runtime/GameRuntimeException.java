package cn.managame.runtime;

import cn.managame.FrameworkErrorCodes;

/**
 * A task the runtime refused; {@link #errorCode} is one of {@link FrameworkErrorCodes}. It records no stack trace, since
 * refusals come in floods, often on an EventLoop, and its code and message say what happened.
 */
public class GameRuntimeException extends RuntimeException {
    private final int errorCode;

    public GameRuntimeException(int errorCode, String message) {
        super(FrameworkErrorCodes.describe(errorCode) + ": " + message, null, true, false);
        this.errorCode = errorCode;
    }

    public int errorCode() { return errorCode; }
}
