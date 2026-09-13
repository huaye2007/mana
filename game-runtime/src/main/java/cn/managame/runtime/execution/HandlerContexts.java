package cn.managame.runtime.execution;

import cn.managame.runtime.context.HandlerContext;

/** Dynamically scoped context, restored even after an inline invocation fails. */
public final class HandlerContexts {
    private HandlerContexts() {}
    private static final ScopedValue<Frame> CURRENT = ScopedValue.newInstance();
    private record Frame(Object runtime, Object domain, HandlerContext context) {}
    public static HandlerContext current() {
        HandlerContext result = currentOrNull();
        if (result == null) throw new IllegalStateException("No current HandlerContext");
        return result;
    }
    public static HandlerContext currentOrNull() { return CURRENT.isBound() ? CURRENT.get().context() : null; }
    static boolean belongsTo(Object runtime) { return CURRENT.isBound() && CURRENT.get().runtime() == runtime; }
    static Object currentDomain() { return CURRENT.isBound() ? CURRENT.get().domain() : null; }
    static void run(Object runtime, HandlerContext context, Runnable action) {
        run(runtime, belongsTo(runtime) ? currentDomain() : null, context, action);
    }
    static void run(Object runtime, Object domain, HandlerContext context, Runnable action) {
        ScopedValue.where(CURRENT, new Frame(runtime, domain, context)).run(action);
    }
}
