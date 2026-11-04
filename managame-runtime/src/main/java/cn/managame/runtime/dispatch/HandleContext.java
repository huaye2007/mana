package cn.managame.runtime.dispatch;

import cn.managame.Metadata;
import cn.managame.runtime.RequestContext;
import cn.managame.runtime.Route;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The context of a dispatched message. It runs on the handler's route, acts for the source's business identity
 * with its metadata, and answers through what the application passed to {@link Dispatcher#dispatch}: the
 * application, not the framework, knows its packet format, and its callbacks hold the way back, such as the
 * connection and the packet's sequence number. A message dispatched without a way to answer expects none, and reply
 * and fail then return false.
 */
public final class HandleContext implements RequestContext {
    static final String[] NO_ARGS = {};
    private final Route route;
    private final Metadata metadata;
    private final Consumer<Object> onReply;
    private final FailCallback onFail;
    private final AtomicBoolean answered = new AtomicBoolean();

    HandleContext(Route route, Metadata metadata, Consumer<Object> onReply, FailCallback onFail) {
        this.route = route;
        this.metadata = metadata;
        this.onReply = onReply;
        this.onFail = onFail;
    }

    @Override public int domain() { return route.domain(); }
    @Override public long routeKey() { return route.routeKey(); }
    @Override public byte businessType() { return route.businessType(); }
    @Override public long businessId() { return route.businessId(); }
    @Override public Metadata metadata() { return metadata; }

    @Override public boolean reply(Object result) {
        if (onReply == null || !answered.compareAndSet(false, true)) return false;
        onReply.accept(result);
        return true;
    }

    @Override public boolean fail(int errorCode) { return fail(errorCode, NO_ARGS); }

    /** {@code args} reach the application's {@link FailCallback} as they are. */
    @Override public boolean fail(int errorCode, String... args) {
        Objects.requireNonNull(args, "args");
        if (onFail == null || !answered.compareAndSet(false, true)) return false;
        onFail.onFail(errorCode, args);
        return true;
    }
}
