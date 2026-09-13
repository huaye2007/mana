package cn.managame.runtime.route;


/**
 * Marker for application-defined route namespaces, used as class literals (e.g. Player.class).
 * No instances or registration are required. Exact Class identity, together with a long key,
 * identifies a serial route within one GameRuntime. This marker supplies no scheduling policy.
 */
public interface RouteType {}
