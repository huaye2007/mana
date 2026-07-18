package cn.managame.jpa.core.exception;

/**
 * 瞬时性写失败的统一标记：网络抖动、连接断开、可重新执行的并发冲突、写超时，
 * 以及可能由显式扩容或并行 Schema 迁移解除的数据过大限制。
 * <p>
 * 这类失败重试通常能成功，{@code FlushScheduler} 会重试到 maxRetries 上限。
 * 与之相对，重复键、类型错误和需要业务重新加载合并的乐观锁冲突不属于此类。
 */
public class RetriableWriteException extends GameJpaException {

    public RetriableWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
