package cn.managame.data.error;
public final class DataLoadException extends RuntimeException {
    public DataLoadException(String message) { super(message); }
    public DataLoadException(String message, Throwable cause) { super(message, cause); }
}
