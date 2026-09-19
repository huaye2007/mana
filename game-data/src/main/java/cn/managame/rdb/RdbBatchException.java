package cn.managame.rdb;

import cn.managame.core.write.BatchResult;
import cn.managame.core.write.BatchWriteException;

final class RdbBatchException extends BatchWriteException {
    private static final long serialVersionUID = 1L;
    private final boolean retryable;
    RdbBatchException(String message, boolean retryable, BatchResult result, Throwable cause) {
        super(message, result, cause);
        this.retryable = retryable;
    }
    boolean retryable() { return retryable; }
}
