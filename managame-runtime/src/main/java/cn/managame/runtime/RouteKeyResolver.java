package cn.managame.runtime;

/**
 * How one domain finds the route key of a message whose handler names no routeKey of its own: from the source
 * context, such as the acting player's id, or however else the application decides. A handler routing by a member of
 * its message, such as its guild id, names it in its annotation instead.
 */
@FunctionalInterface
public interface RouteKeyResolver {
    /**
     * The route key for {@code message} arriving from {@code source}: the context of the connection, the
     * publishing task, the calling node or the HTTP request. Runs on the receiving thread and must not block; an
     * exception means the message cannot be routed.
     */
    long routeKey(Context source, Object message);
}
