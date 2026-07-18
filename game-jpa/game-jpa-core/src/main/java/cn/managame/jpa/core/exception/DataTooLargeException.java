package cn.managame.jpa.core.exception;

/**
 * 写入数据超过后端字段、文档或消息大小限制。
 * <p>
 * 该异常允许异步写回重试，以便显式的自动扩容或并行执行的 Schema 迁移生效；
 * 如果限制没有发生变化，重试达到上限后仍会进入永久失败处理器。
 */
public class DataTooLargeException extends RetriableWriteException {

    public DataTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }
}
