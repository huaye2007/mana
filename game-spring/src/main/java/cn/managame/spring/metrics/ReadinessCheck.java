package cn.managame.spring.metrics;

/**
 * One condition the readiness endpoint reports. The framework contributes a check for each managed component
 * that can stop accepting work; an application adds its own as ordinary beans, for example "this Node is
 * attached to a Router" or "the first world snapshot is loaded".
 *
 * <p>A check is called on an HTTP thread outside the Runtime's Route executors, so it must answer from state
 * it already has and must not block, dispatch or take a lock a saturated instance would be holding — the
 * whole point of the endpoint is that it still answers when the instance is in trouble.
 */
public interface ReadinessCheck {
    /** Short dotted name reported when this check is not ready, for example {@code runtime.accepting}. */
    String name();

    /** Whether this condition currently holds. Must not throw; a throwing check makes the instance not ready. */
    boolean ready();
}
