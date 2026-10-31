package cn.managame.runtime.dispatch;

import cn.managame.Metadata;
import cn.managame.runtime.Context;
import cn.managame.runtime.RequestContext;
import cn.managame.runtime.Route;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The context of a dispatched message. It runs on the handler's route, acts for the source's business identity
 * with its metadata, and answers through what the application passed to {@link Dispatcher#dispatch}: the
 * application, not the framework, knows its packet format. A message dispatched without a way to answer expects
 * none, and reply and fail then return false.
 */
public final class HandleContext implements RequestContext {
    private final Route route;
    private final Context source;
    private final Consumer<Object> onReply;
    private final IntConsumer onFail;
    private final AtomicBoolean answered = new AtomicBoolean();

    HandleContext(Route route, Context source, Consumer<Object> onReply, IntConsumer onFail) {
        this.route = route;
        this.source = source;
        this.onReply = onReply;
        this.onFail = onFail;
    }

    @Override public int domain() { return route.domain(); }
    @Override public long routeKey() { return route.routeKey(); }
    @Override public byte businessType() { return route.businessType(); }
    @Override public long businessId() { return route.businessId(); }
    @Override public Metadata metadata() { return source.metadata(); }

    /** The context the message came from, as the application built it, for example a session with its connection. */
    public Context source() { return source; }

    @Override public boolean reply(Object result) {
        if (onReply == null || !answered.compareAndSet(false, true)) return false;
        onReply.accept(result);
        return true;
    }

    @Override public boolean fail(int errorCode) {
        if (onFail == null || !answered.compareAndSet(false, true)) return false;
        onFail.accept(errorCode);
        return true;
    }
}
