package cn.managame.jpa.core.exception;

/**
 * 可通过重新执行解决的瞬时并发写冲突，例如数据库死锁、事务回滚或主节点切换。
 * <p>
 * 不包含需要重新加载并合并业务状态的乐观锁版本冲突；后者仍由
 * {@link OptimisticLockException} 表示并按确定性失败处理。
 */
public class ConcurrentWriteException extends RetriableWriteException {

    public ConcurrentWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
