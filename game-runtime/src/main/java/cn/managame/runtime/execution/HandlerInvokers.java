package cn.managame.runtime.execution;

import cn.managame.runtime.context.CommandHandlerInvocation;

import java.lang.invoke.*;
import java.util.List;
import static cn.managame.runtime.execution.HandlerBindings.*;

/** Binds invocation shapes once. Does not emit handler classes or bytecode. */
final class HandlerInvokers {
    private HandlerInvokers() {}

    @FunctionalInterface interface CommandInvoker {
        void invoke(CommandHandlerInvocation invocation, Object routeValue) throws Throwable;
    }
    @FunctionalInterface interface EventInvoker { void invoke(Object event) throws Throwable; }
    @FunctionalInterface interface CronInvoker { void invoke() throws Throwable; }
    @FunctionalInterface private interface Argument {
        Object resolve(CommandHandlerInvocation invocation, Object routeValue);
    }

    static CommandInvoker command(MethodHandle target, List<ArgumentSlot> slots, int[] arguments, int routeSlot) {
        Argument[] readers = new Argument[arguments.length];
        int[] reuse = new int[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            int slot = arguments[i];
            var resolver = slots.get(slot).resolver();
            readers[i] = slot == routeSlot ? (inv, route) -> route : (inv, route) -> resolver.apply(inv);
            reuse[i] = -1;
            for (int j = 0; j < i; j++) if (arguments[j] == slot) { reuse[i] = j; break; }
        }
        MethodHandle exact = target.asType(MethodType.genericMethodType(arguments.length).changeReturnType(void.class));
        return switch (arguments.length) {
            case 1 -> one(exact, readers[0]);
            case 2 -> two(exact, readers[0], readers[1], reuse[1] == 0);
            case 3 -> three(exact, readers[0], readers[1], readers[2], reuse[1] == 0, reuse[2]);
            default -> array(exact.asSpreader(Object[].class, arguments.length), readers, reuse);
        };
    }

    private static CommandInvoker one(MethodHandle target, Argument first) {
        return (inv, route) -> { target.invokeExact(first.resolve(inv, route)); };
    }

    private static CommandInvoker two(MethodHandle target, Argument first, Argument second, boolean same) {
        return (inv, route) -> {
            Object a = first.resolve(inv, route);
            Object b = same ? a : second.resolve(inv, route);
            target.invokeExact(a, b);
        };
    }

    private static CommandInvoker three(MethodHandle target, Argument first, Argument second, Argument third,
                                        boolean same, int reuseThird) {
        return (inv, route) -> {
            Object a = first.resolve(inv, route);
            Object b = same ? a : second.resolve(inv, route);
            Object c = reuseThird == 0 ? a : reuseThird == 1 ? b : third.resolve(inv, route);
            target.invokeExact(a, b, c);
        };
    }

    private static CommandInvoker array(MethodHandle target, Argument[] readers, int[] reuse) {
        return (inv, route) -> {
            // General signatures use one array, with duplicate values reused in place.
            Object[] arguments = new Object[readers.length];
            for (int i = 0; i < arguments.length; i++)
                arguments[i] = reuse[i] >= 0 ? arguments[reuse[i]] : readers[i].resolve(inv, route);
            target.invokeExact(arguments);
        };
    }

    static EventInvoker event(MethodHandle target) {
        MethodHandle exact = target.asType(MethodType.methodType(void.class, Object.class));
        return event -> { exact.invokeExact(event); };
    }

    static CronInvoker cron(MethodHandle target) {
        MethodHandle exact = target.asType(MethodType.methodType(void.class));
        return () -> { exact.invokeExact(); };
    }
}
