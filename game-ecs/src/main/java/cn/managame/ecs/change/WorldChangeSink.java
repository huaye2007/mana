package cn.managame.ecs.change;

/**
 * Receives coalesced world changes, normally to enqueue client synchronization.
 *
 * <p>Sinks run synchronously on the world's owner thread after a tick. They
 * should serialize or copy the batch and hand it to a non-blocking outbound
 * queue instead of performing blocking network I/O.</p>
 */
@FunctionalInterface
public interface WorldChangeSink {

    /**
     * Accepts one ordered batch.
     */
    void accept(WorldChangeBatch batch);
}
