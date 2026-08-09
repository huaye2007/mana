package cn.managame.jpa.core.write;

/**
 * 异步写任务：一条待落库的变更。
 * <p>
 * 合并通道按 {@code entityName + id} 去重，同一 key 的多次操作只保留最终状态（{@link Op#SAVE}
 * 或 {@link Op#DELETE}）；合并由 {@code MergeBuffer} 用整对象替换完成，本类不提供原地合并。
 * <p>
 * 任务持有实体<b>引用</b>而不是快照，这是性能取舍：调用方在提交后不应再并发修改同一实体对象。
 */
public class WriteTask {

    public enum Op { SAVE, DELETE }

    private final String entityName;
    private final Op op;
    private final Object entity;
    private final Object id;

    /**
     * 重试次数。只由刷盘线程递增、由提交/回灌线程按 key 传递，是<b>尽力而为</b>的预算计数，
     * 不追求跨线程精确可见——少数几次误差只影响放弃时机，不影响正确性。
     */
    private int retryCount;

    public WriteTask(String entityName, Op op, Object entity, Object id) {
        this.entityName = entityName;
        this.op = op;
        this.entity = entity;
        this.id = id;
    }

    public String entityName() { return entityName; }
    public Op op() { return op; }
    public Object entity() { return entity; }
    public Object id() { return id; }
    public int retryCount() { return retryCount; }

    public void incrementRetry() { retryCount++; }

    /**
     * 接手同一 key 上另一个任务的重试预算，取两者较大值。
     * <p>
     * 没有这一步，一条<b>确定性写失败</b>的热点记录（比如永远撞唯一索引）只要业务还在持续更新，
     * 每次新提交都会把重试次数清零，永远到不了 maxRetries，也就永远不会进 permanentFailureHandler——
     * 坏记录会一直卡在队列里反复失败。取 max 而非累加，保证多线程重复调用是幂等的。
     */
    public void inheritRetryCount(WriteTask previous) {
        if (previous != null && previous.retryCount > this.retryCount) {
            this.retryCount = previous.retryCount;
        }
    }

    @Override
    public String toString() {
        return "WriteTask[" + entityName + " " + op + " id=" + id + " retry=" + retryCount + "]";
    }
}
