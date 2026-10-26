package cn.managame.runtime.event;

import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.annotation.AnnotatedMethod;
import cn.managame.runtime.annotation.EventMethod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Delivers events to {@link EventMethod} subscribers, each as a task of its own. Subscribers are registered once,
 * at startup, by passing the objects that declare them:
 *
 * <pre>{@code
 * @Domain(GUILD)
 * class GuildSystem {
 *     @EventMethod void onJoined(GuildJoined event) { ... }
 * }
 * EventBus events = EventBus.builder(runtime).register(guildSystem, questSystem).build();
 * events.publish(new GuildJoined(playerId, guildId));
 * }</pre>
 *
 * A subscriber runs in its domain, on the routing key the domain's resolver finds for the event and the
 * publishing context, acting for the publisher's business identity with its metadata; outside a game task the
 * publisher is an empty context. Subscribers receive events of exactly their class. Delivery always goes through
 * the runtime, so a subscriber runs after the publishing task, never inside it, and one route receives events in
 * the order they were published. Thread-safe.
 */
public final class EventBus {
    private static final GameContext NO_PUBLISHER = GameContext.of(0, 0);
    private final GameRuntime runtime;
    private final Map<Class<?>, AnnotatedMethod[]> subscribers;

    private EventBus(Builder b) {
        runtime = b.runtime;
        Map<Class<?>, AnnotatedMethod[]> frozen = new HashMap<>();
        b.subscribers.forEach((type, list) -> frozen.put(type, list.toArray(AnnotatedMethod[]::new)));
        subscribers = Map.copyOf(frozen);
    }

    public static Builder builder(GameRuntime runtime) { return new Builder(runtime); }

    /**
     * Hands the event to every subscriber of its class. A subscriber whose route cannot be found, or whose task
     * the runtime refuses, does not stop the others; the first such failure is thrown afterwards, with the rest
     * suppressed.
     */
    public void publish(Object event) {
        AnnotatedMethod[] targets = subscribers.get(Objects.requireNonNull(event, "event").getClass());
        if (targets == null) return;
        Context publisher = Context.hasCurrent() ? Context.current() : NO_PUBLISHER;
        RuntimeException failure = null;
        for (AnnotatedMethod subscriber : targets) {
            try {
                GameContext context = new GameContext(runtime.route(subscriber.domain(), publisher, event), publisher.metadata());
                runtime.execute(context, task -> subscriber.invoke(task, event));
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    public static final class Builder {
        private final GameRuntime runtime;
        private final Map<Class<?>, List<AnnotatedMethod>> subscribers = new HashMap<>();

        private Builder(GameRuntime runtime) { this.runtime = Objects.requireNonNull(runtime, "runtime"); }

        /**
         * Registers every {@link EventMethod} of each object; subscribers to one event class are called in
         * registration order. A subscriber declaring a context other than GameContext or its supertypes throws
         * IllegalArgumentException.
         */
        public Builder register(Object... targets) {
            for (Object target : targets) {
                for (AnnotatedMethod subscriber : AnnotatedMethod.scan(target, EventMethod.class)) {
                    subscriber.requireContext(GameContext.class);
                    subscribers.computeIfAbsent(subscriber.messageType(), type -> new ArrayList<>()).add(subscriber);
                }
            }
            return this;
        }

        public EventBus build() { return new EventBus(this); }
    }
}
