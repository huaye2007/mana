package cn.managame.runtime.diagnostics;

public final class CallbackFailureException extends RuntimeException {
    private final int code;
    public CallbackFailureException(int code) { super("Callback failed with code " + code); this.code = code; }
    public int code() { return code; }
}
