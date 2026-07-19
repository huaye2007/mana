package cn.managame.runtime.executor;

/**
 * Immediate result of submitting a game task to an executor group.
 */
public enum TaskSubmissionResult {

    ACCEPTED,
    REJECTED_OVERLOADED,
    REJECTED_SHUTDOWN,
    REJECTED_NO_GROUP;

    public boolean isAccepted() {
        return this == ACCEPTED;
    }
}
