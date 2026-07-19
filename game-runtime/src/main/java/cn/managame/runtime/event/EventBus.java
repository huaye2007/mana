package cn.managame.runtime.event;

import cn.managame.common.context.Metadata;
import cn.managame.runtime.annotation.EventHandler;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.context.GameEventTaskContext;
import cn.managame.runtime.context.GameTaskContext;
import cn.managame.runtime.context.GameTaskContextHolder;
import cn.managame.runtime.context.GameTaskType;
import cn.managame.runtime.executor.ExecutorGroupRegistry;
import cn.managame.runtime.executor.TaskSubmissionResult;
import cn.managame.runtime.invoke.Invokers;
import cn.managame.runtime.runnable.GameEventTaskRunnable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-process event bus. The first publication freezes registration, after which publication is
 * lock-free. Event types match exactly rather than polymorphically.
 */
public final class EventBus {

    private static final EventBus INSTANCE = new EventBus();

    private final Map<Class<?>, List<EventMeta>> listeners = new HashMap<>();
    private volatile boolean frozen;

    public static EventBus getInstance() {
        return INSTANCE;
    }

    /** Registers all valid methods atomically for one handler instance. */
    public synchronized void register(Object handler) {
        if (frozen) {
            throw new IllegalStateException("event bus is frozen after first publish");
        }
        Objects.requireNonNull(handler, "handler");
        Class<?> type = handler.getClass();
        EventHandler handlerAnnotation = type.getAnnotation(EventHandler.class);
        if (handlerAnnotation == null) {
            throw new IllegalArgumentException(type.getName() + " is not annotated with @EventHandler");
        }

        List<EventMeta> pending = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            EventMethod methodAnnotation = method.getAnnotation(EventMethod.class);
            if (methodAnnotation == null) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            String methodName = type.getName() + "#" + method.getName();
            if (parameterTypes.length != 1 || !IGameEvent.class.isAssignableFrom(parameterTypes[0])) {
                throw new IllegalStateException("@EventMethod " + methodName
                        + " must have exactly one IGameEvent parameter");
            }
            if (method.getReturnType() != void.class) {
                throw new IllegalStateException("@EventMethod " + methodName + " must return void");
            }
            Invokers.requireInvokable(method);
            byte group = methodAnnotation.group() != 0
                    ? methodAnnotation.group()
                    : handlerAnnotation.group();
            pending.add(new EventMeta(handler, method, group, parameterTypes[0], methodAnnotation.order()));
        }

        for (EventMeta listener : pending) {
            listeners.computeIfAbsent(listener.getEventType(), ignored -> new ArrayList<>()).add(listener);
        }
        for (Class<?> eventType : pending.stream().map(EventMeta::getEventType).distinct().toList()) {
            listeners.get(eventType).sort(Comparator.comparingInt(EventMeta::getOrder));
        }
    }

    /** Legacy fire-and-forget entry point. Use {@link #tryPublishEvent} when rejection matters. */
    public void publishEvent(IGameEvent event) {
        tryPublishEvent(event);
    }

    public EventPublishResult tryPublishEvent(IGameEvent event) {
        Objects.requireNonNull(event, "event");
        freeze();
        return publish(GameTaskContextHolder.current(), event, false);
    }

    /**
     * Publishes with an explicit source context. Inline execution is allowed only when that same
     * context is currently bound and owns the event key.
     */
    public void publishEvent(GameEventTaskContext sourceContext, IGameEvent event) {
        tryPublishEvent(sourceContext, event);
    }

    public EventPublishResult tryPublishEvent(GameEventTaskContext sourceContext, IGameEvent event) {
        Objects.requireNonNull(sourceContext, "sourceContext");
        Objects.requireNonNull(event, "event");
        freeze();
        return publish(sourceContext, event, true);
    }

    private EventPublishResult publish(GameTaskContext sourceContext, IGameEvent event,
                                       boolean requireSameBoundContext) {
        List<EventMeta> eventListeners = listeners.get(event.getClass());
        if (eventListeners == null) {
            return EventPublishResult.NO_LISTENERS;
        }

        int inline = 0;
        int accepted = 0;
        int rejected = 0;
        long routerKey = event.routerKey();
        for (EventMeta listener : eventListeners) {
            boolean boundContextMatches = !requireSameBoundContext
                    || GameTaskContextHolder.current() == sourceContext;
            if (boundContextMatches && canRunInline(sourceContext, listener.getGroup(), routerKey)) {
                listener.invoke(sourceContext, event);
                inline++;
            } else if (dispatchToGroup(sourceContext, event, listener).isAccepted()) {
                accepted++;
            } else {
                rejected++;
            }
        }
        return new EventPublishResult(inline, accepted, rejected);
    }

    private TaskSubmissionResult dispatchToGroup(GameTaskContext sourceContext, IGameEvent event,
                                                  EventMeta listener) {
        byte busType = sourceContext != null ? sourceContext.getBusType() : 0;
        long busId = sourceContext != null ? sourceContext.getBusId() : 0;
        Metadata[] metadata = sourceContext != null ? sourceContext.getMetadatas() : null;
        GameEventTaskContext targetContext = new GameEventTaskContext(GameTaskType.EVENT,
                listener.getGroup(), event.routerKey(), busType, busId, metadata);
        return ExecutorGroupRegistry.getInstance().tryExecute(
                new GameEventTaskRunnable(targetContext, listener, event));
    }

    /** Hosts may freeze explicitly after startup; the first publish does this automatically. */
    public void freeze() {
        if (frozen) {
            return;
        }
        synchronized (this) {
            if (frozen) {
                return;
            }
            listeners.replaceAll((type, values) -> List.copyOf(values));
            frozen = true;
        }
    }

    public boolean isFrozen() {
        return frozen;
    }

    private static boolean canRunInline(GameTaskContext context, byte listenerGroup, long routerKey) {
        return context != null
                && listenerGroup == context.getGroup()
                && routerKey == context.getRouterKey();
    }
}
