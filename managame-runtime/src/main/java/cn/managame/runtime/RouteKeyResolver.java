package cn.managame.runtime;

/**
 * How one domain finds the routing key of a message arriving for it. The application decides: from the source
 * context, such as the acting player's id, or from the message, such as its guild id or room id.
 */
@FunctionalInterface
public interface RouteKeyResolver {
    /**
     * The routing key for {@code message} arriving from {@code source}: the context of the connection, the
     * publishing task, the calling node or the HTTP request. Runs on the receiving thread and must not block; an
     * exception means the message cannot be routed.
     */
    long routingKey(Context source, Object message);
}
