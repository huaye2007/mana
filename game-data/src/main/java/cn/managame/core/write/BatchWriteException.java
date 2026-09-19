package cn.managame.core.write;

import cn.managame.core.DataException;

/** Write failure carrying a precise/partial batch result when the backend can determine it. */
public class BatchWriteException extends DataException {
    private static final long serialVersionUID = 1L;

    private final transient BatchResult result;

    public BatchWriteException(String message, BatchResult result, Throwable cause) {
        super(message, cause);
        this.result = result;
    }

    public BatchWriteException(String message, BatchResult result) {
        super(message);
        this.result = result;
    }

    public BatchResult result() { return result; }
}
