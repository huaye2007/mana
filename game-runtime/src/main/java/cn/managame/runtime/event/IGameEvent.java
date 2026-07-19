package cn.managame.runtime.event;

/**
 * Event payload routed by the in-process event bus.
 *
 * <p>Implementations must be deeply immutable. A published instance may be observed by
 * listeners on different executor groups, so mutable fields, arrays, collections and other
 * mutable object graphs are not permitted.</p>
 */
public interface IGameEvent {

    long routerKey();
}
