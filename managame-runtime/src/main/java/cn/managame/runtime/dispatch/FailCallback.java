package cn.managame.runtime.dispatch;

/**
 * Delivers a dispatched message's failure in the application's format: the error code and the args the handler
 * failed with, an empty array when it gave none, as the framework's own failures do. Called at most once, on
 * whichever thread fails the message; it must not block, since that may be an EventLoop.
 */
@FunctionalInterface
public interface FailCallback {
    void onFail(int errorCode, String[] args);
}
