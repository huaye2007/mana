package cn.managame.data;

/**
 * Receives every change and log the framework gives up saving, so the application can keep it elsewhere. Called
 * one failure at a time, from a saving thread, or from the inserting thread for a log refused by a full queue.
 */
@FunctionalInterface
public interface DataErrorHandler {
    void onError(DataFailure failure);
}
