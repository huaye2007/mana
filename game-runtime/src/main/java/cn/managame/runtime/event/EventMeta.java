package cn.managame.runtime.event;


import cn.managame.runtime.context.GameTaskContext;
import cn.managame.runtime.exception.GameTaskExceptionHandlers;
import cn.managame.runtime.invoke.Invokers;
import java.lang.reflect.Method;


public final class EventMeta {

    private final Object handlerInstance;
    private final Method method;
    private final Invokers.EventInvoker invoker;
    private final byte group;
    private final Class<?> eventType;
    private final int order;


    EventMeta(Object handlerInstance, Method method, byte group,
              Class<?> eventType, int order) {
        this.handlerInstance = handlerInstance;
        this.method = method;
        this.invoker = Invokers.eventInvoker(method);
        this.group = group;
        this.eventType = eventType;
        this.order = order;
    }

    public Object getHandlerInstance() {
        return handlerInstance;
    }

    public Method getMethod() {
        return method;
    }

    public byte getGroup() {
        return group;
    }

    public Class<?> getEventType() {
        return eventType;
    }

    public int getOrder() {
        return order;
    }

    /**
     * 调用单个监听方法。异常路由到全局异常处理器，不中断同事件的其他监听者。
     *
     * <p>context 仅用于异常处理/监控的语义信息，监听方法本身不接收 context（签名是
     * {@code method(event)}），因此任何任务类型的上下文都可透传，内联执行时直接复用
     * 发布方上下文即可，避免凭空造出一个 EVENT 上下文导致 taskType 失真。</p>
     */
    public void invoke(GameTaskContext context, IGameEvent gameEvent) {
        try {
            invoker.invoke(handlerInstance, gameEvent);
        } catch (Throwable e) {
            GameTaskExceptionHandlers.handle(context, e);
        }
    }
}
