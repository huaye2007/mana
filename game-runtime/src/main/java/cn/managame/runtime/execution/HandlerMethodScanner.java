package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.Cron;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.annotation.HandlerMethod;

import java.lang.reflect.Method;
import java.util.*;

/** Initialization-only discovery, including inherited methods and invalid non-public declarations. */
final class HandlerMethodScanner {
    private HandlerMethodScanner() {}

    static List<Method> scan(Class<?> type) {
        Map<String, Method> effective = new HashMap<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) add(effective, method);
        }
        for (Method method : type.getMethods()) add(effective, method);
        return effective.values().stream().filter(HandlerMethodScanner::isEntry)
                .sorted(Comparator.comparing(Method::toGenericString)).toList();
    }

    private static void add(Map<String, Method> methods, Method method) {
        if (method.isSynthetic() && !method.isBridge()) return;
        String signature = method.getName() + Arrays.toString(method.getParameterTypes());
        Method previous = methods.putIfAbsent(signature, method);
        // Bridges mask erased declarations in ancestors, but a real declaration in the
        // same class takes precedence when a covariant return produces the same signature.
        if (previous != null && previous.isBridge() && !method.isBridge()
                && previous.getDeclaringClass() == method.getDeclaringClass())
            methods.put(signature, method);
    }

    private static boolean isEntry(Method method) {
        return !method.isBridge() && !method.isSynthetic()
                && (method.isAnnotationPresent(HandlerMethod.class) || method.isAnnotationPresent(EventMethod.class)
                || method.isAnnotationPresent(Cron.class));
    }
}
