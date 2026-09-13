package cn.managame.demo.protocol;

import java.util.List;

/** Owned failure details survive transport callbacks, runtime dispatch and local exceptions. */
public final class GameCallException extends RuntimeException {
    private final int errorCode;
    private final List<String> errorArgs;
    public GameCallException(int errorCode, List<String> errorArgs) { this(errorCode, errorArgs, null); }
    public GameCallException(int errorCode, List<String> errorArgs, Throwable cause) {
        super("Game error " + errorCode + ", args=" + errorArgs, cause);
        if (errorCode <= 0) throw new IllegalArgumentException("Failure code must be positive");
        this.errorCode = errorCode;
        this.errorArgs = List.copyOf(errorArgs);
    }
    public int errorCode() { return errorCode; }
    public List<String> errorArgs() { return errorArgs; }
}
