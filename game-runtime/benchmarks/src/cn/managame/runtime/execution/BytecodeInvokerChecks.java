package cn.managame.runtime.execution;

import cn.managame.runtime.context.CommandHandlerInvocation;
import cn.managame.runtime.context.Metadata;

import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static cn.managame.runtime.execution.HandlerBindings.*;

public final class BytecodeInvokerChecks {
    public static final class Target {
        long result;
        public void duplicate(Object route, String first, String second, int count) {
            if (first != second || !route.equals("route")) throw new AssertionError("Reuse mismatch");
            result = first.length() + count;
        }
        public void hiddenRoute(String input) { result = input.length(); }
        public void throwsFailure(String input) { throw new IllegalStateException(input); }
        public void primitives(boolean z, byte b, short s, char c, int i, long j, float f, double d) {
            result = (z ? 1 : 0) + b + s + c + i + j + (long) f + (long) d;
        }
    }
    static void check(boolean condition) { if (!condition) throw new AssertionError("Invoker check failed"); }
    public static void main(String[] args) throws Throwable {
        for (String flavor : List.of("bound", "bytecode")) {
            Target target = new Target();
            var reads = new AtomicInteger();
            List<ArgumentSlot> slots = List.of(
                new ArgumentSlot(inv -> { throw new AssertionError("Route re-resolved"); }, true),
                new ArgumentSlot(inv -> { reads.incrementAndGet(); return new String("value"); }, false),
                new ArgumentSlot(inv -> 9, false));
            Method method = Target.class.getMethod("duplicate", Object.class, String.class, String.class, int.class);
            var call = bind(flavor, target, method, slots, new int[]{0, 1, 1, 2}, 0);
            var invocation = new CommandHandlerInvocation("unused", null, Metadata.empty());
            call.invoke(invocation, "route");
            check(target.result == 14 && reads.get() == 1);
            var hidden = bind(flavor, target, Target.class.getMethod("hiddenRoute", String.class),
                    slots, new int[]{1}, 0);
            hidden.invoke(invocation, "route");
            check(target.result == 5 && reads.get() == 2);
            var failure = bind(flavor, target, Target.class.getMethod("throwsFailure", String.class),
                    slots, new int[]{1}, 0);
            try { failure.invoke(invocation, "route"); throw new AssertionError("Expected business failure"); }
            catch (IllegalStateException expected) { check(expected.getMessage().equals("value")); }
            Object[] values = {true, (byte)2, (short)3, 'a', 5, 6L, 7F, 8D};
            List<ArgumentSlot> primitiveSlots = new ArrayList<>();
            for (Object value : values) primitiveSlots.add(new ArgumentSlot(inv -> value, false));
            var primitive = bind(flavor, target, Target.class.getMethod("primitives", boolean.class, byte.class,
                    short.class, char.class, int.class, long.class, float.class, double.class),
                    primitiveSlots, new int[]{0,1,2,3,4,5,6,7}, -1);
            primitive.invoke(invocation, null);
            check(target.result == 129);
        }
        System.out.println("Bytecode invoker parity checks passed: reuse, hidden route, exceptions, all primitive types.");
    }
    static HandlerInvokers.CommandInvoker bind(String flavor, Object bean, Method method,
            List<ArgumentSlot> slots, int[] arguments, int routeSlot) throws IllegalAccessException {
        if (flavor.equals("bytecode")) return BytecodeInvokers.command(bean, method, slots, arguments, routeSlot);
        return HandlerInvokers.command(MethodHandles.lookup().unreflect(method).bindTo(bean), slots, arguments, routeSlot);
    }
}
