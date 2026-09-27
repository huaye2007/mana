package cn.managame.runtime.error;

public class RuntimeDispatchException extends RuntimeException {
    private final int errorCode;
    public RuntimeDispatchException(int errorCode, String message) { super(message); this.errorCode = errorCode; }
    public int errorCode() { return errorCode; }
}
