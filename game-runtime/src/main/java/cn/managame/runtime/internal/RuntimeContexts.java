package cn.managame.runtime.internal;

import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.context.Context;

public final class RuntimeContexts {
    private static final ScopedValue<Context> CURRENT = ScopedValue.newInstance();
    private static final ScopedValue<GameRuntime> OWNER = ScopedValue.newInstance();
    private RuntimeContexts() {}
    public static Context current() { return CURRENT.get(); }
    public static Context currentOrNull() { return CURRENT.isBound() ? CURRENT.get() : null; }
    public static <T extends Context> T current(Class<T> type) { return type.cast(current()); }
    public static GameRuntime ownerOrNull() { return OWNER.isBound() ? OWNER.get() : null; }
    static boolean ownedBy(GameRuntime runtime) { return OWNER.isBound() && OWNER.get() == runtime; }
    static void run(GameRuntime runtime, Context context, Runnable task) {
        ScopedValue.where(CURRENT, context).where(OWNER, runtime).run(task);
    }
}
