package cn.managame.runtime.context;

import cn.managame.runtime.internal.RuntimeContexts;

/** Read-only view of the context bound to the current runtime execution. */
public final class Contexts {
    private Contexts() {}
    public static Context current() { return RuntimeContexts.current(); }
    public static Context currentOrNull() { return RuntimeContexts.currentOrNull(); }
    public static <T extends Context> T current(Class<T> type) { return type.cast(current()); }
}
