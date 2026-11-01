package cn.managame.runtime;

/**
 * A context whose origin waits for an answer, such as a player request, a request from another server or an
 * HTTP request. Business logic answers through it without knowing how the answer travels: each source's
 * implementation encodes and delivers it. Only the first answer counts, and it may come from any thread, also
 * after the task returned, for example from the callback of a call to another server.
 *
 * <p>A request is always answered: when its task throws, the runtime fails it with RUNTIME_EXECUTION_ERROR, and when
 * {@link GameRuntime#execute} refuses its task, with the refusal's code.
 */
public interface RequestContext extends Context {
    /** Answers with {@code result}; false when the request was already answered. */
    boolean reply(Object result);

    /**
     * Answers with a failure; {@code errorCode} is one of the FrameworkErrorCodes or an application code. False
     * when the request was already answered.
     */
    boolean fail(int errorCode);
}
