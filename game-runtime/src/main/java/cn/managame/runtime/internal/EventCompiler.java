package cn.managame.runtime.internal;

import static cn.managame.runtime.internal.Reflection.bind;
import static cn.managame.runtime.internal.Reflection.invalid;
import static cn.managame.runtime.internal.Reflection.methods;
import static cn.managame.runtime.internal.Reflection.requireVoidMethod;

import cn.managame.runtime.event.Event;
import cn.managame.runtime.event.EventMethod;
import cn.managame.runtime.internal.RuntimeBindings.CronBinding;
import cn.managame.runtime.internal.RuntimeBindings.CronKey;
import cn.managame.runtime.internal.RuntimeBindings.EventBinding;
import cn.managame.runtime.internal.RuntimeBindings.EventSubscribers;
import cn.managame.runtime.time.GameTime;
import cn.managame.runtime.timer.Cron;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles {@code @EventMethod} subscribers and {@code @Cron} methods. */
final class EventCompiler {
    private EventCompiler() {}

    /**
     * Event type → subscribers in ascending {@code order}, with the Domain its listeners declared. One
     * event carries one Domain, so listeners of the same type that declare different Domains can never
     * both run on a Route that owns their state; that is a build failure rather than a publish-time one.
     */
    static Map<Class<?>, EventSubscribers> events(List<Object> targets, Set<Integer> domains) {
        Map<Class<?>, List<EventBinding>> listeners = new HashMap<>();
        Map<Class<?>, Integer> declared = new HashMap<>();
        for (Object target : targets) {
            for (Method m : methods(target)) {
                EventMethod event = m.getAnnotation(EventMethod.class);
                if (event == null) continue;
                requireVoidMethod(m);
                if (m.getParameterCount() != 1 || !Event.class.isAssignableFrom(m.getParameterTypes()[0]))
                    throw invalid("Event method needs one Event: " + m);
                Class<?> type = m.getParameterTypes()[0];
                if (event.domain() != 0) {
                    if (!domains.contains(event.domain())) throw invalid("Unregistered event listener domain: " + m);
                    Integer previous = declared.putIfAbsent(type, event.domain());
                    if (previous != null && previous != event.domain())
                        throw invalid("Listeners of " + type.getName() + " declare conflicting domains "
                                + previous + " and " + event.domain() + ": " + m);
                }
                listeners.computeIfAbsent(type, ignored -> new ArrayList<>())
                        .add(new EventBinding(event.order(), bind(target, m).asType(MethodType.methodType(void.class, Object.class))));
            }
        }
        Map<Class<?>, EventSubscribers> compiled = new HashMap<>();
        listeners.forEach((type, value) -> compiled.put(type, new EventSubscribers(declared.getOrDefault(type, 0),
                value.stream().sorted(Comparator.comparingInt(EventBinding::order)).toList())));
        return Map.copyOf(compiled);
    }

    /** Cron methods keyed by declaring class and method name; expressions are validated before any resource starts. */
    static List<CronBinding> crons(List<Object> targets, Set<Integer> domains, ZoneId zone) {
        List<CronBinding> compiled = new ArrayList<>();
        Set<CronKey> keys = new HashSet<>();
        for (Object target : targets) {
            for (Method m : methods(target)) {
                Cron cron = m.getAnnotation(Cron.class);
                if (cron == null) continue;
                requireVoidMethod(m);
                if (m.getParameterCount() != 0 || !domains.contains(cron.domain()) || cron.routeKey() == 0)
                    throw invalid("Invalid cron method: " + m);
                CronSchedule schedule = new CronSchedule(cron.value(), zone);
                schedule.next(Instant.ofEpochMilli(GameTime.currentTimeMillis()));
                CronKey key = new CronKey(m.getDeclaringClass(), m.getName());
                if (!keys.add(key)) throw invalid("Duplicate cron " + key);
                compiled.add(new CronBinding(key, cron.domain(), cron.routeKey(), schedule, bind(target, m)));
            }
        }
        return List.copyOf(compiled);
    }
}
