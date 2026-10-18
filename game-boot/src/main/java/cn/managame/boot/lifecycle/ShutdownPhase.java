package cn.managame.boot.lifecycle;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether this instance has begun leaving rotation. It is set once, at the very start of shutdown and before
 * admission closes, so readiness can report failure while the instance is still serving what arrives.
 *
 * <p>This is the piece that makes a readiness endpoint useful for taking an instance out of rotation rather
 * than only for noticing a sick one. Without it, admission and readiness change in the same instant and
 * everything a load balancer sends between that instant and its next probe is refused.
 */
public final class ShutdownPhase {
    private final AtomicBoolean leaving = new AtomicBoolean();

    /** Whether shutdown has begun. Readable from any thread, including an HTTP thread answering a probe. */
    public boolean leavingRotation() { return leaving.get(); }

    /** Marks the instance as leaving rotation; true the first time, false afterwards. Idempotent. */
    public boolean leaveRotation() { return leaving.compareAndSet(false, true); }
}
