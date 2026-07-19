package cn.managame.runtime.event;

/**
 * Immediate fan-out result. Accepted listeners may still fail later during execution.
 */
public record EventPublishResult(int inlineExecutions, int acceptedSubmissions, int rejectedSubmissions) {

    public static final EventPublishResult NO_LISTENERS = new EventPublishResult(0, 0, 0);

    public boolean fullyAccepted() {
        return rejectedSubmissions == 0;
    }

    public int listenerCount() {
        return inlineExecutions + acceptedSubmissions + rejectedSubmissions;
    }
}
