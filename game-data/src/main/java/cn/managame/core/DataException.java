package cn.managame.core;

public class DataException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DataException(String message) { super(message); }
    public DataException(String message, Throwable cause) { super(message, cause); }
}
