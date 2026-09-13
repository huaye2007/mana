package cn.managame.runtime.diagnostics;

import java.util.concurrent.RejectedExecutionException;

/** Permanent rejection: this runtime no longer accepts work. */
public final class RuntimeClosedException extends RejectedExecutionException {
    public RuntimeClosedException() { super("Runtime is closed"); }
}
