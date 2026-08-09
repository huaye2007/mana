package cn.managame.jpa.core.exception;

import java.util.Arrays;

/**
 * 批量写入部分失败：整批没有提交，但后端能指出批内<b>哪几条</b>记录是坏的。
 * <p>
 * 异步刷盘据此一次性把好记录和坏记录分开——好记录整批重放（1 次往返），坏记录单独处理——
 * 省去「二分拆批探测坏记录」的 O(log n) 次额外往返。后端定位不出下标时不要抛本异常，
 * 让调度器退回二分隔离即可。
 * <p>
 * {@code cause} 必须是翻译后的具体失败原因（如 {@link DataTooLargeException}、
 * {@link DuplicateKeyException}），调度器仍按 cause 链判定重试语义。
 */
public class PartialBatchException extends GameJpaException {

    private final int[] failedIndexes;

    /**
     * @param failedIndexes 批内失败记录的下标（相对本批第一条为 0），不能为空
     */
    public PartialBatchException(String message, int[] failedIndexes, Throwable cause) {
        super(message, cause);
        if (failedIndexes == null || failedIndexes.length == 0) {
            throw new IllegalArgumentException("failedIndexes must not be empty");
        }
        this.failedIndexes = failedIndexes.clone();
    }

    /** 批内失败记录的下标副本。 */
    public int[] failedIndexes() {
        return failedIndexes.clone();
    }

    /** 失败记录条数。 */
    public int failedCount() {
        return failedIndexes.length;
    }

    @Override
    public String toString() {
        return super.toString() + " failedIndexes=" + Arrays.toString(failedIndexes);
    }
}
