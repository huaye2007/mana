package com.github.huaye2007.mana.runtime.invoke;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 注册期把反射 {@link Method} 编译成 {@link LambdaMetafactory} 生成的调用器，
 * 热路径调用等价于普通虚方法调用，避免 {@code Method.invoke} 的装箱和访问检查开销。
 *
 * <p>约束：目标方法及其声明类都必须 public。注册期不满足直接抛
 * {@link IllegalStateException}，不留到运行期才暴露。</p>
 */
public final class Invokers {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /** 事件监听调用器：handler.method(event) */
    @FunctionalInterface
    public interface EventInvoker {
        void invoke(Object handler, Object event);
    }

    /** 命令处理调用器：controller.method(context, message) */
    @FunctionalInterface
    public interface CommandInvoker {
        void invoke(Object controller, Object para, Object message);
    }

    /** 路由键提取器：message.routerKeyMethod()，int/Integer/Long 返回值统一适配成 long */
    @FunctionalInterface
    public interface RouterKeyExtractor {
        long extract(Object message);
    }

    private Invokers() {
    }

    /**
     * 注册期 fail-fast：方法和声明类都必须 public，否则调用器无法生成。
     */
    public static void requireInvokable(Method method) {
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalStateException("method must be public: "
                    + method.getDeclaringClass().getName() + "#" + method.getName());
        }
        if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            throw new IllegalStateException("declaring class must be public: "
                    + method.getDeclaringClass().getName());
        }
    }

    public static EventInvoker eventInvoker(Method method) {
        MethodType sam = MethodType.methodType(void.class, Object.class, Object.class);
        MethodType instantiated = MethodType.methodType(void.class,
                method.getDeclaringClass(), method.getParameterTypes()[0]);
        return (EventInvoker) createLambda(method, EventInvoker.class, sam, instantiated);
    }

    public static CommandInvoker commandInvoker(Method method) {
        // samType 必须与函数式接口 CommandInvoker.invoke(Object, Object, Object) 的擦除签名一致，
        // 三个参数都是 Object；具体类型由 instantiated（实例化签名）携带，由 LambdaMetafactory
        // 在调用点插入向下转型。中间槽误写成 GameTaskContext 会导致 controller 首参（如 PlayerSession/
        // busId）不是其子类型而抛 LambdaConversionException。
        MethodType sam = MethodType.methodType(void.class, Object.class, Object.class, Object.class);
        MethodType instantiated = MethodType.methodType(void.class,
                method.getDeclaringClass(), method.getParameterTypes()[0], method.getParameterTypes()[1]);
        return (CommandInvoker) createLambda(method, CommandInvoker.class, sam, instantiated);
    }

    public static RouterKeyExtractor routerKeyExtractor(Method method) {
        MethodType sam = MethodType.methodType(long.class, Object.class);
        MethodType instantiated = MethodType.methodType(long.class, method.getDeclaringClass());
        return (RouterKeyExtractor) createLambda(method, RouterKeyExtractor.class, sam, instantiated);
    }

    private static Object createLambda(Method method, Class<?> samInterface,
                                       MethodType samType, MethodType instantiatedType) {
        requireInvokable(method);
        try {
            MethodHandle handle = LOOKUP.unreflect(method);
            CallSite callSite = LambdaMetafactory.metafactory(LOOKUP, samInterfaceMethodName(samInterface),
                    MethodType.methodType(samInterface), samType, handle, instantiatedType);
            return callSite.getTarget().invoke();
        } catch (Throwable e) {
            throw new IllegalStateException("create invoker failed for "
                    + method.getDeclaringClass().getName() + "#" + method.getName(), e);
        }
    }

    private static String samInterfaceMethodName(Class<?> samInterface) {
        return samInterface == RouterKeyExtractor.class ? "extract" : "invoke";
    }
}
