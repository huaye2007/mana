package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.route.RouteType;

/** Static business fixtures; only the invokers are generated dynamically during the benchmark. */
public final class BytecodeFixtures {
    private BytecodeFixtures() {}
    public interface Player extends RouteType {}
    public record Id(long value) {}
    public record Extra(long value) {}
    public abstract static class Payload {
        public final int index, route, protocol;
        public final long value, sentAt;
        Payload(int index, int route, int protocol, long value, long sentAt) {
            this.index = index; this.route = route; this.protocol = protocol; this.value = value; this.sentAt = sentAt;
        }
    }
    public static final class Req0 extends Payload {
        Req0(int index, int route, long value, long sentAt) { super(index, route, 0, value, sentAt); }
    }
    public static final class Req1 extends Payload {
        Req1(int index, int route, long value, long sentAt) { super(index, route, 1, value, sentAt); }
    }
    public static final class Req2 extends Payload {
        Req2(int index, int route, long value, long sentAt) { super(index, route, 2, value, sentAt); }
    }
    public static final class Req3 extends Payload {
        Req3(int index, int route, long value, long sentAt) { super(index, route, 3, value, sentAt); }
    }
    public static final class Req4 extends Payload {
        Req4(int index, int route, long value, long sentAt) { super(index, route, 4, value, sentAt); }
    }
    public static final class Req5 extends Payload {
        Req5(int index, int route, long value, long sentAt) { super(index, route, 5, value, sentAt); }
    }
    public static final class Req6 extends Payload {
        Req6(int index, int route, long value, long sentAt) { super(index, route, 6, value, sentAt); }
    }
    public static final class Req7 extends Payload {
        Req7(int index, int route, long value, long sentAt) { super(index, route, 7, value, sentAt); }
    }
    public static final class Req8 extends Payload {
        Req8(int index, int route, long value, long sentAt) { super(index, route, 8, value, sentAt); }
    }
    public static final class Req9 extends Payload {
        Req9(int index, int route, long value, long sentAt) { super(index, route, 9, value, sentAt); }
    }
    public static final class Req10 extends Payload {
        Req10(int index, int route, long value, long sentAt) { super(index, route, 10, value, sentAt); }
    }
    public static final class Req11 extends Payload {
        Req11(int index, int route, long value, long sentAt) { super(index, route, 11, value, sentAt); }
    }
    public static final class Req12 extends Payload {
        Req12(int index, int route, long value, long sentAt) { super(index, route, 12, value, sentAt); }
    }
    public static final class Req13 extends Payload {
        Req13(int index, int route, long value, long sentAt) { super(index, route, 13, value, sentAt); }
    }
    public static final class Req14 extends Payload {
        Req14(int index, int route, long value, long sentAt) { super(index, route, 14, value, sentAt); }
    }
    public static final class Req15 extends Payload {
        Req15(int index, int route, long value, long sentAt) { super(index, route, 15, value, sentAt); }
    }
    public static final class Req16 extends Payload {
        Req16(int index, int route, long value, long sentAt) { super(index, route, 16, value, sentAt); }
    }
    public static final class Req17 extends Payload {
        Req17(int index, int route, long value, long sentAt) { super(index, route, 17, value, sentAt); }
    }
    public static final class Req18 extends Payload {
        Req18(int index, int route, long value, long sentAt) { super(index, route, 18, value, sentAt); }
    }
    public static final class Req19 extends Payload {
        Req19(int index, int route, long value, long sentAt) { super(index, route, 19, value, sentAt); }
    }
    public static final class Req20 extends Payload {
        Req20(int index, int route, long value, long sentAt) { super(index, route, 20, value, sentAt); }
    }
    public static final class Req21 extends Payload {
        Req21(int index, int route, long value, long sentAt) { super(index, route, 21, value, sentAt); }
    }
    public static final class Req22 extends Payload {
        Req22(int index, int route, long value, long sentAt) { super(index, route, 22, value, sentAt); }
    }
    public static final class Req23 extends Payload {
        Req23(int index, int route, long value, long sentAt) { super(index, route, 23, value, sentAt); }
    }
    public static final class Req24 extends Payload {
        Req24(int index, int route, long value, long sentAt) { super(index, route, 24, value, sentAt); }
    }
    public static final class Req25 extends Payload {
        Req25(int index, int route, long value, long sentAt) { super(index, route, 25, value, sentAt); }
    }
    public static final class Req26 extends Payload {
        Req26(int index, int route, long value, long sentAt) { super(index, route, 26, value, sentAt); }
    }
    public static final class Req27 extends Payload {
        Req27(int index, int route, long value, long sentAt) { super(index, route, 27, value, sentAt); }
    }
    public static final class Req28 extends Payload {
        Req28(int index, int route, long value, long sentAt) { super(index, route, 28, value, sentAt); }
    }
    public static final class Req29 extends Payload {
        Req29(int index, int route, long value, long sentAt) { super(index, route, 29, value, sentAt); }
    }
    public static final class Req30 extends Payload {
        Req30(int index, int route, long value, long sentAt) { super(index, route, 30, value, sentAt); }
    }
    public static final class Req31 extends Payload {
        Req31(int index, int route, long value, long sentAt) { super(index, route, 31, value, sentAt); }
    }
    static Class<?> requestType(int protocol) {
        return switch (protocol) {
            case 0 -> Req0.class;
            case 1 -> Req1.class;
            case 2 -> Req2.class;
            case 3 -> Req3.class;
            case 4 -> Req4.class;
            case 5 -> Req5.class;
            case 6 -> Req6.class;
            case 7 -> Req7.class;
            case 8 -> Req8.class;
            case 9 -> Req9.class;
            case 10 -> Req10.class;
            case 11 -> Req11.class;
            case 12 -> Req12.class;
            case 13 -> Req13.class;
            case 14 -> Req14.class;
            case 15 -> Req15.class;
            case 16 -> Req16.class;
            case 17 -> Req17.class;
            case 18 -> Req18.class;
            case 19 -> Req19.class;
            case 20 -> Req20.class;
            case 21 -> Req21.class;
            case 22 -> Req22.class;
            case 23 -> Req23.class;
            case 24 -> Req24.class;
            case 25 -> Req25.class;
            case 26 -> Req26.class;
            case 27 -> Req27.class;
            case 28 -> Req28.class;
            case 29 -> Req29.class;
            case 30 -> Req30.class;
            case 31 -> Req31.class;
            default -> throw new IllegalArgumentException("protocol");
        };
    }
    static Payload request(int protocol, int index, int route, long value, long sentAt) {
        return switch (protocol) {
            case 0 -> new Req0(index, route, value, sentAt);
            case 1 -> new Req1(index, route, value, sentAt);
            case 2 -> new Req2(index, route, value, sentAt);
            case 3 -> new Req3(index, route, value, sentAt);
            case 4 -> new Req4(index, route, value, sentAt);
            case 5 -> new Req5(index, route, value, sentAt);
            case 6 -> new Req6(index, route, value, sentAt);
            case 7 -> new Req7(index, route, value, sentAt);
            case 8 -> new Req8(index, route, value, sentAt);
            case 9 -> new Req9(index, route, value, sentAt);
            case 10 -> new Req10(index, route, value, sentAt);
            case 11 -> new Req11(index, route, value, sentAt);
            case 12 -> new Req12(index, route, value, sentAt);
            case 13 -> new Req13(index, route, value, sentAt);
            case 14 -> new Req14(index, route, value, sentAt);
            case 15 -> new Req15(index, route, value, sentAt);
            case 16 -> new Req16(index, route, value, sentAt);
            case 17 -> new Req17(index, route, value, sentAt);
            case 18 -> new Req18(index, route, value, sentAt);
            case 19 -> new Req19(index, route, value, sentAt);
            case 20 -> new Req20(index, route, value, sentAt);
            case 21 -> new Req21(index, route, value, sentAt);
            case 22 -> new Req22(index, route, value, sentAt);
            case 23 -> new Req23(index, route, value, sentAt);
            case 24 -> new Req24(index, route, value, sentAt);
            case 25 -> new Req25(index, route, value, sentAt);
            case 26 -> new Req26(index, route, value, sentAt);
            case 27 -> new Req27(index, route, value, sentAt);
            case 28 -> new Req28(index, route, value, sentAt);
            case 29 -> new Req29(index, route, value, sentAt);
            case 30 -> new Req30(index, route, value, sentAt);
            case 31 -> new Req31(index, route, value, sentAt);
            default -> throw new IllegalArgumentException("protocol");
        };
    }
    public abstract static class Target {
        final long[] sums;
        long[] latencies;
        Target(int routes) { sums = new long[routes]; }
        final void record(Id id, Payload request, long extra, int methodSalt) {
            sums[(int) id.value()] += (id.value() ^ request.value) + extra + methodSalt;
            if (latencies != null) latencies[request.index] = System.nanoTime() - request.sentAt;
        }
    }
    @Handler(routeType = Player.class)
    public static final class Target2 extends Target {
        Target2(int routes) { super(routes); }
        @HandlerMethod public void message0(Id id, Req0 request) {
            record(id, request, 0, 0);
        }
        @HandlerMethod public void message1(Id id, Req1 request) {
            record(id, request, 0, 1);
        }
        @HandlerMethod public void message2(Id id, Req2 request) {
            record(id, request, 0, 2);
        }
        @HandlerMethod public void message3(Id id, Req3 request) {
            record(id, request, 0, 3);
        }
        @HandlerMethod public void message4(Id id, Req4 request) {
            record(id, request, 0, 4);
        }
        @HandlerMethod public void message5(Id id, Req5 request) {
            record(id, request, 0, 5);
        }
        @HandlerMethod public void message6(Id id, Req6 request) {
            record(id, request, 0, 6);
        }
        @HandlerMethod public void message7(Id id, Req7 request) {
            record(id, request, 0, 7);
        }
        @HandlerMethod public void message8(Id id, Req8 request) {
            record(id, request, 0, 8);
        }
        @HandlerMethod public void message9(Id id, Req9 request) {
            record(id, request, 0, 9);
        }
        @HandlerMethod public void message10(Id id, Req10 request) {
            record(id, request, 0, 10);
        }
        @HandlerMethod public void message11(Id id, Req11 request) {
            record(id, request, 0, 11);
        }
        @HandlerMethod public void message12(Id id, Req12 request) {
            record(id, request, 0, 12);
        }
        @HandlerMethod public void message13(Id id, Req13 request) {
            record(id, request, 0, 13);
        }
        @HandlerMethod public void message14(Id id, Req14 request) {
            record(id, request, 0, 14);
        }
        @HandlerMethod public void message15(Id id, Req15 request) {
            record(id, request, 0, 15);
        }
        @HandlerMethod public void message16(Id id, Req16 request) {
            record(id, request, 0, 16);
        }
        @HandlerMethod public void message17(Id id, Req17 request) {
            record(id, request, 0, 17);
        }
        @HandlerMethod public void message18(Id id, Req18 request) {
            record(id, request, 0, 18);
        }
        @HandlerMethod public void message19(Id id, Req19 request) {
            record(id, request, 0, 19);
        }
        @HandlerMethod public void message20(Id id, Req20 request) {
            record(id, request, 0, 20);
        }
        @HandlerMethod public void message21(Id id, Req21 request) {
            record(id, request, 0, 21);
        }
        @HandlerMethod public void message22(Id id, Req22 request) {
            record(id, request, 0, 22);
        }
        @HandlerMethod public void message23(Id id, Req23 request) {
            record(id, request, 0, 23);
        }
        @HandlerMethod public void message24(Id id, Req24 request) {
            record(id, request, 0, 24);
        }
        @HandlerMethod public void message25(Id id, Req25 request) {
            record(id, request, 0, 25);
        }
        @HandlerMethod public void message26(Id id, Req26 request) {
            record(id, request, 0, 26);
        }
        @HandlerMethod public void message27(Id id, Req27 request) {
            record(id, request, 0, 27);
        }
        @HandlerMethod public void message28(Id id, Req28 request) {
            record(id, request, 0, 28);
        }
        @HandlerMethod public void message29(Id id, Req29 request) {
            record(id, request, 0, 29);
        }
        @HandlerMethod public void message30(Id id, Req30 request) {
            record(id, request, 0, 30);
        }
        @HandlerMethod public void message31(Id id, Req31 request) {
            record(id, request, 0, 31);
        }
    }
    @Handler(routeType = Player.class)
    public static final class Target4 extends Target {
        Target4(int routes) { super(routes); }
        @HandlerMethod public void message0(Id id, Req0 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 0);
        }
        @HandlerMethod public void message1(Id id, Req1 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 1);
        }
        @HandlerMethod public void message2(Id id, Req2 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 2);
        }
        @HandlerMethod public void message3(Id id, Req3 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 3);
        }
        @HandlerMethod public void message4(Id id, Req4 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 4);
        }
        @HandlerMethod public void message5(Id id, Req5 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 5);
        }
        @HandlerMethod public void message6(Id id, Req6 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 6);
        }
        @HandlerMethod public void message7(Id id, Req7 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 7);
        }
        @HandlerMethod public void message8(Id id, Req8 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 8);
        }
        @HandlerMethod public void message9(Id id, Req9 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 9);
        }
        @HandlerMethod public void message10(Id id, Req10 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 10);
        }
        @HandlerMethod public void message11(Id id, Req11 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 11);
        }
        @HandlerMethod public void message12(Id id, Req12 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 12);
        }
        @HandlerMethod public void message13(Id id, Req13 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 13);
        }
        @HandlerMethod public void message14(Id id, Req14 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 14);
        }
        @HandlerMethod public void message15(Id id, Req15 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 15);
        }
        @HandlerMethod public void message16(Id id, Req16 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 16);
        }
        @HandlerMethod public void message17(Id id, Req17 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 17);
        }
        @HandlerMethod public void message18(Id id, Req18 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 18);
        }
        @HandlerMethod public void message19(Id id, Req19 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 19);
        }
        @HandlerMethod public void message20(Id id, Req20 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 20);
        }
        @HandlerMethod public void message21(Id id, Req21 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 21);
        }
        @HandlerMethod public void message22(Id id, Req22 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 22);
        }
        @HandlerMethod public void message23(Id id, Req23 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 23);
        }
        @HandlerMethod public void message24(Id id, Req24 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 24);
        }
        @HandlerMethod public void message25(Id id, Req25 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 25);
        }
        @HandlerMethod public void message26(Id id, Req26 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 26);
        }
        @HandlerMethod public void message27(Id id, Req27 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 27);
        }
        @HandlerMethod public void message28(Id id, Req28 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 28);
        }
        @HandlerMethod public void message29(Id id, Req29 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 29);
        }
        @HandlerMethod public void message30(Id id, Req30 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 30);
        }
        @HandlerMethod public void message31(Id id, Req31 request, Extra first, Extra second) {
            record(id, request, first.value() + second.value(), 31);
        }
    }
}
