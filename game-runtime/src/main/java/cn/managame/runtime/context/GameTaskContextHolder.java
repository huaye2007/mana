package cn.managame.runtime.context;

/**
 * 隐式任务上下文绑定。
 *
 * <p>{@link GameTaskType#COMMAND} 的上下文作为方法参数显式传递，不经过这里；
 * EVENT/TIMER/CALLBACK 的上下文在任务执行期间隐式绑定到当前线程：
 * 虚拟线程绑定到 {@link ScopedValue}，平台线程绑定到 {@link ThreadLocal}。
 * 业务代码只在需要时通过 {@link #current()} 获取。</p>
 */
public final class GameTaskContextHolder {

    private static final ScopedValue<GameTaskContext> SCOPED = ScopedValue.newInstance();

    private static final ThreadLocal<GameTaskContext> THREAD_LOCAL = new ThreadLocal<>();

    private GameTaskContextHolder() {
    }

    /**
     * 在绑定 context 的前提下执行 runnable，执行完毕后恢复原绑定。
     */
    public static void runWith(GameTaskContext context, Runnable runnable) {
        if (Thread.currentThread().isVirtual()) {
            ScopedValue.where(SCOPED, context).run(runnable);
        } else {
            GameTaskContext prev = THREAD_LOCAL.get();
            THREAD_LOCAL.set(context);
            try {
                runnable.run();
            } finally {
                if (prev == null) {
                    THREAD_LOCAL.remove();
                } else {
                    THREAD_LOCAL.set(prev);
                }
            }
        }
    }

    /**
     * 获取当前线程绑定的任务上下文，未绑定时返回 null。
     */
    public static GameTaskContext current() {
        if (Thread.currentThread().isVirtual()) {
            return SCOPED.isBound() ? SCOPED.get() : null;
        }
        return THREAD_LOCAL.get();
    }
}
