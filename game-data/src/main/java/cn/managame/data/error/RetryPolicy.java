package cn.managame.data.error;
@FunctionalInterface
public interface RetryPolicy {
    boolean shouldRetry(DataFailure failure);
}
