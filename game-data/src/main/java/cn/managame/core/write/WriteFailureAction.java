package cn.managame.core.write;

/** Decision for the current failed batch only. No deferred/manual replay is supported. */
public enum WriteFailureAction {
    /** The backend proved the failed attempt can be retried safely and the error is transient. */
    RETRY,
    /** The current batch cannot be safely handled. Record it and continue with later batches. */
    FAIL
}
