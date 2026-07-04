package cn.managame.runtime.timer;

/**
 * 定时任务句柄，可在触发前取消。
 */
public interface Timeout {

    /**
     * 取消任务。已触发或已取消时返回 false。周期任务取消后不再有后续触发。
     */
    boolean cancel();

    boolean isCancelled();

    boolean isExpired();
}
