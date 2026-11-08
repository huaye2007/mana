package cn.managame.runtime;

import cn.managame.FrameworkErrorCodes;
import cn.managame.metrics.Counter;
import cn.managame.metrics.Metrics;

/**
 * What a task is, as metrics and the slow-task log name it: a handler, or for a task written by hand the class it was
 * written in. There are as many as there are handlers and classes, each made once with its meters, so a task pays no
 * name and no tags. Public only to be reachable from the runtime's entries; not API.
 */
public final class TaskKind {
    /** The codes the runtime itself answers with, which say nothing about the business. */
    private static final int FIRST_RUNTIME_CODE = 3001, LAST_RUNTIME_CODE = 3999;
    private final String name;
    private final Counter calls, failed, businessFailed, runNanos;

    TaskKind(String name, Metrics metrics) {
        this.name = name;
        calls = metrics.counter("managame.runtime.handler.calls", "handler", name);
        failed = metrics.counter("managame.runtime.handler.failed", "handler", name);
        businessFailed = metrics.counter("managame.runtime.handler.business_failed", "handler", name);
        runNanos = metrics.counter("managame.runtime.handler.run_nanos", "handler", name);
    }

    String name() { return name; }

    /** A task of this kind ran for {@code nanos}; {@code completed} is false when it threw. */
    void ran(long nanos, boolean completed) {
        calls.increment();
        runNanos.increment(nanos);
        if (!completed) failed.increment();
    }

    /** A request of this kind was answered with {@code code}: a failure unless it is success or the runtime's own. */
    public void answered(int code) {
        if (code != FrameworkErrorCodes.SUCCESS && (code < FIRST_RUNTIME_CODE || code > LAST_RUNTIME_CODE)) businessFailed.increment();
    }
}
