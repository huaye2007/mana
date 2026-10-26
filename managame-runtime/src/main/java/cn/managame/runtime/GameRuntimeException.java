package cn.managame.runtime;

import cn.managame.FrameworkErrorCodes;

/** A task the runtime refused; {@link #errorCode} is one of {@link FrameworkErrorCodes}. */
public class GameRuntimeException extends RuntimeException {
    private final int errorCode;

    public GameRuntimeException(int errorCode, String message) {
        super(FrameworkErrorCodes.describe(errorCode) + ": " + message);
        this.errorCode = errorCode;
    }

    public int errorCode() { return errorCode; }
}
