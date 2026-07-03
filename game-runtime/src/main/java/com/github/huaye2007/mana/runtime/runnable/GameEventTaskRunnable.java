package com.github.huaye2007.mana.runtime.runnable;

import com.github.huaye2007.mana.runtime.context.GameEventTaskContext;
import com.github.huaye2007.mana.runtime.context.GameTaskContext;
import com.github.huaye2007.mana.runtime.context.GameTaskContextHolder;
import com.github.huaye2007.mana.runtime.event.EventMeta;
import com.github.huaye2007.mana.runtime.event.IGameEvent;

/**
 * 单个事件监听者的派发任务。当监听者无法在发布线程内联执行（异组或异 routerKey）时，
 * {@link com.github.huaye2007.mana.runtime.event.EventBus} 按事件 routerKey 把"调用这一个监听者"封装成本
 * 任务，投递到监听者所在执行器组；同一事件的多个监听者各自对应一个本任务，互不影响。
 *
 * <p>执行期间把事件上下文绑定到 {@link GameTaskContextHolder}，使监听者内部再发事件也能
 * 命中"同组同 routerKey 内联"。监听者异常已在 {@link EventMeta#invoke} 内兜底，不会中断
 * worker，也不会影响同事件的其它监听者。</p>
 */
public class GameEventTaskRunnable implements IGameTaskRunnable {

    private final GameEventTaskContext gameEventTaskContext;
    private final EventMeta eventMeta;
    private final IGameEvent gameEvent;

    public GameEventTaskRunnable(GameEventTaskContext gameEventTaskContext, EventMeta eventMeta, IGameEvent gameEvent) {
        this.gameEventTaskContext = gameEventTaskContext;
        this.eventMeta = eventMeta;
        this.gameEvent = gameEvent;
    }

    @Override
    public void run() {
        GameTaskContextHolder.runWith(gameEventTaskContext,
                () -> eventMeta.invoke(gameEventTaskContext, gameEvent));
    }

    @Override
    public GameTaskContext getGameTaskContext() {
        return gameEventTaskContext;
    }
}
