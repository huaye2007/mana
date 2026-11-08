package cn.managame.runtime.dispatch;

import cn.managame.Metadata;
import cn.managame.runtime.RequestContext;
import cn.managame.runtime.Route;
import cn.managame.runtime.TaskKind;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The context of a dispatched message. It runs on the handler's route, acts for the source's business identity
 * with its metadata, and answers through what the application passed to {@link Dispatcher#dispatch}: the
 * application, not the framework, knows its packet format, and its callback holds the way back, such as the
 * connection and the packet's sequence number. A handler answers by returning a {@link HandleResult}, or through
 * {@code reply} and {@code fail}, also later from any thread. A message dispatched without a way to answer expects
 * none, and reply and fail then return false.
 */
public final class HandleContext implements RequestContext {
    private final Route route;
    private final Metadata metadata;
    private final Consumer<HandleResult> onAnswer;
    private final TaskKind kind;
    private final AtomicBoolean answered = new AtomicBoolean();

    HandleContext(Route route, Metadata metadata, Consumer<HandleResult> onAnswer, TaskKind kind) {
        this.route = route;
        this.metadata = metadata;
        this.onAnswer = onAnswer;
        this.kind = kind;
    }

    @Override public TaskKind taskKind() { return kind; }

    @Override public int domain() { return route.domain(); }
    @Override public long routeKey() { return route.routeKey(); }
    @Override public byte businessType() { return route.businessType(); }
    @Override public long businessId() { return route.businessId(); }
    @Override public Metadata metadata() { return metadata; }

    @Override public boolean reply(Object result) { return answer(HandleResult.ok(result)); }

    @Override public boolean fail(int errorCode) { return fail(errorCode, HandleResult.NO_ARGS); }

    /** {@code args} reach the application's callback as they are, in the {@link HandleResult}. */
    @Override public boolean fail(int errorCode, String... args) { return answer(HandleResult.fail(errorCode, args)); }

    /** Answers with {@code result}; false when the message expects no answer or was already answered. */
    boolean answer(HandleResult result) {
        if (onAnswer == null || !answered.compareAndSet(false, true)) return false;
        kind.answered(result.code());
        onAnswer.accept(result);
        return true;
    }
}
