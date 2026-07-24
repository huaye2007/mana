package cn.managame.ecs.change;

import cn.managame.ecs.EntityId;

import java.util.List;
import java.util.Objects;

/**
 * Ordered, non-empty batch of coalesced world changes.
 *
 * @param sequence monotonically increasing sequence within one world
 * @param changes changes in deterministic production order
 */
public record WorldChangeBatch(long sequence, List<WorldChange> changes) {

    public WorldChangeBatch {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive: " + sequence);
        }
        Objects.requireNonNull(changes, "changes");
        changes = List.copyOf(changes);
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("A change batch must not be empty");
        }
    }

    /**
     * Returns changes affecting one entity.
     */
    public List<WorldChange> changesFor(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId");
        return changes.stream()
                .filter(change -> change.entityId().equals(entityId))
                .toList();
    }
}
