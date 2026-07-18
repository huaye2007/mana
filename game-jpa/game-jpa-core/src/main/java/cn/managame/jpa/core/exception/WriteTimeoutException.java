package cn.managame.jpa.core.exception;

/** 写操作在后端完成前超时，允许异步写回重新执行。 */
public class WriteTimeoutException extends RetriableWriteException {

    public WriteTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
