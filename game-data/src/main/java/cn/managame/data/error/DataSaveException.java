package cn.managame.data.error;
public final class DataSaveException extends RuntimeException {
    public DataSaveException(String message) { super(message); }
    public DataSaveException(String message, Throwable cause) { super(message, cause); }
}
