package cn.managame.runtime;

import java.util.Arrays;
import java.util.Objects;

/**
 * Thrown from anywhere in a task to fail its request with {@link #errorCode} and {@link #args}, as
 * {@link RequestContext#fail(int, String...)} would, so that business code returns its result and leaves failures to
 * this:
 *
 * <pre>{@code
 * Shop.BuyRes buy(Shop.BuyReq req) {
 *     if (gold < price) throw new FailException(NOT_ENOUGH_GOLD, String.valueOf(price));
 *     ...
 *     return result;
 * }
 *
 * @HandleMethod void buy(HandleContext context, Shop.BuyReq req) { context.reply(shop.buy(req)); }
 * }</pre>
 *
 * The runtime fails the task's request with it and hands it to no error handler, since it is an answer, not an error;
 * a request already answered stays as it was. Thrown from a task with no request, such as a timer's or an event's, it
 * goes to the error handler as any exception does. It records no stack trace, so throwing one costs an allocation.
 */
public class FailException extends RuntimeException {
    private final int errorCode;
    private final String[] args;

    /** {@code args} reach the request's source as they are, for it to place in its format. */
    public FailException(int errorCode, String... args) {
        super(null, null, true, false);
        this.errorCode = errorCode;
        this.args = Objects.requireNonNull(args, "args");
    }

    public int errorCode() { return errorCode; }

    public String[] args() { return args; }

    @Override public String getMessage() {
        return "error code " + errorCode + (args.length == 0 ? "" : " " + Arrays.toString(args));
    }
}
