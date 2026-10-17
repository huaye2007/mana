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

    /** Event type → subscribers in ascending {@code order}. */
    static Map<Class<?>, List<EventBinding>> events(List<Object> targets) {
        Map<Class<?>, List<EventBinding>> compiled = new HashMap<>();
        for (Object target : targets) {
            for (Method m : methods(target)) {
                EventMethod event = m.getAnnotation(EventMethod.class);
                if (event == null) continue;
                requireVoidMethod(m);
                if (m.getParameterCount() != 1 || !Event.class.isAssignableFrom(m.getParameterTypes()[0]))
                    throw invalid("Event method needs one Event: " + m);
                compiled.computeIfAbsent(m.getParameterTypes()[0], ignored -> new ArrayList<>())
                        .add(new EventBinding(event.order(), bind(target, m).asType(MethodType.methodType(void.class, Object.class))));
            }
        }
        compiled.replaceAll((type, value) -> value.stream().sorted(Comparator.comparingInt(EventBinding::order)).toList());
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
