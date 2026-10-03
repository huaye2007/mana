package cn.managame.demo.common.runtime;

import cn.managame.runtime.timer.Cron;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/** Finds task owners without instantiating unrelated Beans or the Runtime being built. */
final class CronBeans {
    private CronBeans() {}

    static List<Object> discover(ConfigurableListableBeanFactory beans) {
        var targets = new ArrayList<Object>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (String name : beans.getBeanNamesForType(Object.class, true, false)) {
            Class<?> type = beans.getType(name, false);
            if (type == null || !hasCron(type)) continue;
            if (!beans.isSingleton(name)) throw new IllegalArgumentException("Cron Bean must be singleton: " + name);
            Object target = beans.getBean(name);
            if (AopUtils.isAopProxy(target)) throw new IllegalArgumentException("Cron Bean proxies are not supported: " + name);
            if (seen.add(target)) targets.add(target);
        }
        return List.copyOf(targets);
    }

    private static boolean hasCron(Class<?> type) {
        for (Class<?> parent = type; parent != null && parent != Object.class; parent = parent.getSuperclass())
            for (Method method : parent.getDeclaredMethods())
                if (method.isAnnotationPresent(Cron.class)) return true;
        for (Method method : type.getMethods())
            if (method.isAnnotationPresent(Cron.class)) return true;
        return false;
    }
}
