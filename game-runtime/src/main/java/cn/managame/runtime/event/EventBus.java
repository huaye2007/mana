package cn.managame.runtime.event;


import cn.managame.runtime.annotation.EventHandler;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.context.GameEventTaskContext;
import cn.managame.runtime.context.GameTaskContext;
import cn.managame.runtime.context.GameTaskContextHolder;
import cn.managame.runtime.context.GameTaskType;
import cn.managame.common.context.Metadata;
import cn.managame.runtime.executor.ExecutorGroupRegistry;
import cn.managame.runtime.invoke.Invokers;
import cn.managame.runtime.runnable.GameEventTaskRunnable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * 进程内事件总线。
 *
 * <p><b>使用契约：</b>{@link #register(Object)} 只允许在启动期（开始处理任务前）
 * 单线程完成，运行期注册表只读，因此内部使用非线程安全的 HashMap 无需加锁。</p>
 *
 * <p>事件类型为<b>精确匹配</b>：注册在父类/接口上的监听不会被子类事件触发。</p>
 */
public final class EventBus {

    private final Map<Class<?>, List<EventMeta>> map = new HashMap<>();

    private final static EventBus INSTANCE = new EventBus();

    public static EventBus getInstance(){
        return INSTANCE;
    }

    public void register(Object handler) {
        Class<?> clazz = handler.getClass();
        EventHandler eventHandler = clazz.getAnnotation(EventHandler.class);
        if (eventHandler == null) {
            throw new IllegalArgumentException(clazz.getName() + " is not annotated with @EventHandler");
        }

        for (Method method : clazz.getDeclaredMethods()) {
            EventMethod em = method.getAnnotation(EventMethod.class);
            if (em == null) {
                continue;
            }
            // 方法级 group 为 0 时继承类级 @EventHandler 的 group；
            // 两级都不指定则落到玩家线程池组（ExecutorGroups.PLAYER）
            byte group = em.group() != 0 ? em.group() : eventHandler.group();
            int order = em.order();
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length != 1) {
                throw new IllegalStateException("@EventMethod " + clazz.getName() + "#" + method.getName() +
                        " must have exactly 1 parameter: (EventObject event)");
            }
            Invokers.requireInvokable(method);
            Class<?> cls = paramTypes[0];
            List<EventMeta> eventMetas = map.computeIfAbsent(cls, k -> new ArrayList<>());

            EventMeta eventMeta = new EventMeta(handler,method,group,cls,order);
            eventMetas.add(eventMeta);
            eventMetas.sort(Comparator.comparingInt(EventMeta::getOrder));
        }
    }

    /**
     * 从业务代码直接发布事件，来源上下文取当前线程绑定的 GameTaskContext。
     *
     * <p>逐个监听者判断：监听者的 group 与当前任务相同、且事件 routerKey 与当前任务
     * 相同（即必然路由到当前线程）时直接内联执行；否则封装成事件任务投递到监听者
     * 所在执行器组。</p>
     *
     * <p>内联执行直接复用当前上下文：监听者本来就跑在当前 worker 上，taskType/busType
     * 等语义信息应保持发布方的原值，而不是被改写成 EVENT。</p>
     */
    public void publishEvent(IGameEvent gameEvent) {
        List<EventMeta> eventMetas = map.get(gameEvent.getClass());
        if (eventMetas == null) {
            return;
        }
        GameTaskContext current = GameTaskContextHolder.current();
        long routerKey = gameEvent.routerKey();
        for (EventMeta e : eventMetas) {
            if (current != null && e.getGroup() == current.getGroup() && routerKey == current.getRouterKey()) {
                e.invoke(current, gameEvent);
            } else {
                dispatchToGroup(current, gameEvent, e);
            }
        }
    }

    /**
     * 以显式事件上下文发布（事件任务自身派发监听者时走这里）。
     *
     * <p>内联条件与 {@link #publishEvent(IGameEvent)} 一致：监听者 group 与上下文相同、
     * 且事件 routerKey 与上下文相同（必然路由到当前 worker）才内联；否则按事件
     * routerKey 投递，保证同 routerKey 串行不被破坏。</p>
     */
    public void publishEvent(GameEventTaskContext gameEventTaskContext, IGameEvent gameEvent){
        List<EventMeta> eventMetas = map.get(gameEvent.getClass());
        if(eventMetas == null){
            return;
        }
        long routerKey = gameEvent.routerKey();
        for(EventMeta e : eventMetas){
            if (e.getGroup() == gameEventTaskContext.getGroup()
                    && routerKey == gameEventTaskContext.getRouterKey()) {
                e.invoke(gameEventTaskContext, gameEvent);
            } else {
                dispatchToGroup(gameEventTaskContext, gameEvent, e);
            }
        }
    }

    /**
     * 监听者无法在当前线程内联执行时，按事件的 routerKey 投递到目标执行器组。
     * sourceContext 为 null（无绑定上下文）时 bus 来源信息置空。
     */
    private void dispatchToGroup(GameTaskContext sourceContext, IGameEvent gameEvent, EventMeta eventMeta) {
        byte busType = sourceContext != null ? sourceContext.getBusType() : 0;
        long busId = sourceContext != null ? sourceContext.getBusId() : 0;
        Metadata[] metadatas = sourceContext != null ? sourceContext.getMetadatas() : null;
        GameEventTaskContext targetContext = new GameEventTaskContext(GameTaskType.EVENT,
                eventMeta.getGroup(), gameEvent.routerKey(), busType, busId, metadatas);
        ExecutorGroupRegistry.getInstance().execute(
                new GameEventTaskRunnable(targetContext, eventMeta, gameEvent));
    }

}
