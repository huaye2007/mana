package cn.managame.runtime;

/**
 * Common RouteKeyResolvers. A handler that routes by a member of its message names it in its annotation instead, for
 * example {@code @HandleMethod(routeKey = "guildId")}.
 */
public final class RouteKeys {
    private RouteKeys() {}

    /**
     * The source's business id: the acting player's id, from the session for a request or from the publishing task for
     * an event. The default of a domain registered without a resolver.
     */
    public static RouteKeyResolver businessId() { return (source, message) -> source.businessId(); }

    /** One key for every message, for a domain that runs everything on one route. */
    public static RouteKeyResolver constant(long key) { return (source, message) -> key; }
}
