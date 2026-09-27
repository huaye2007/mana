package cn.managame.data.error;
@FunctionalInterface
public interface DataErrorHandler {
    void onError(DataFailure failure);
}
