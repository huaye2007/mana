package cn.managame.data.internal;

import static cn.managame.data.DataOperation.DELETE;
import static cn.managame.data.DataOperation.DELETE_INSERT;
import static cn.managame.data.DataOperation.INSERT;

import cn.managame.data.DataOperation;
import cn.managame.data.GroupKeys;
import cn.managame.data.spi.EntityMapper;
import cn.managame.data.spi.EntityType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One entity type's changes not yet written, one per row, and the mapper that writes them. A new change of a row goes
 * into the one waiting, so a row changed many times between rounds is written once, with the state it has then. A
 * round takes every change ({@link #takeAll}), marks those it wrote or gave up, and ends ({@link #endRound}), which
 * removes them and leaves the others to the next round. A change stays here until then, visible to {@link #find} and
 * {@link #findGroup}, so a load never reads a row older than its last change.
 *
 * <p>A change is not touched while a round writes it. A row changed meanwhile gets another change in its place, one
 * that is right whatever becomes of the write in progress: an insert being written counts as a row the database may
 * have, so the change after it replaces the row (DELETE_INSERT) or deletes it.
 *
 * <p>Rows are spread over segments by id, or by group for a grouped type, each with its own lock, so business threads
 * adding changes rarely wait for each other. Only the one thread writing the type takes changes and ends the round.
 *
 * <p>Not API: it may change in any release.
 */
public final class ChangeSet {
    private static final int SEGMENTS = 64;
    final EntityType type;
    final EntityMapper mapper;
    private final boolean grouped;
    private final Segment[] segments = new Segment[SEGMENTS];
    /** What the rounds did since GameData was built, counted by the write thread; read by stats. */
    final AtomicLong written = new AtomicLong(), failures = new AtomicLong(), dropped = new AtomicLong();
    private volatile boolean closed;

    /**
     * The changes of the rows of one segment. Padded to a cache line of its own, so threads locking neighbouring
     * segments do not slow each other down.
     */
    @SuppressWarnings("unused")
    private static final class Segment {
        /** An ungrouped type's changes by id. */
        final HashMap<Object, PendingChange> byId = new HashMap<>();
        /** A grouped type's changes by group, each group's by id. */
        final HashMap<GroupKeys, HashMap<Object, PendingChange>> byGroup = new HashMap<>();
        /** How many changes the segment holds, those a round is writing included. */
        int size;
        long p1, p2, p3, p4, p5, p6, p7;
    }

    public ChangeSet(EntityType type, EntityMapper mapper) {
        this.type = type;
        this.mapper = mapper;
        grouped = !type.groupKeys().isEmpty();
        for (int i = 0; i < SEGMENTS; i++) segments[i] = new Segment();
    }

    public EntityType type() { return type; }

    public EntityMapper mapper() { return mapper; }

    /** Changes waiting to be written, those the round in progress took included. */
    public int waiting() {
        int waiting = 0;
        for (Segment segment : segments) synchronized (segment) { waiting += segment.size; }
        return waiting;
    }

    /** Changes written since GameData was built. */
    public long written() { return written.get(); }

    /** Writes that failed since GameData was built, a batch or a single change each counting once. */
    public long failures() { return failures.get(); }

    /** Changes handed to the DataErrorHandler since GameData was built. */
    public long dropped() { return dropped.get(); }

    /**
     * Adds a change of a row, into the one waiting when there is one. Throws IllegalStateException, changing nothing,
     * when the change cannot follow the waiting one (an insert of a row waiting to be inserted or updated, an update of
     * a row waiting to be deleted) or the change set is closed.
     *
     * @param mapKey the row's map key within {@code group} as its entity has it now; null for an ungrouped type
     * @param entity the entity to write; null only for a delete of a row not in memory
     */
    public void add(DataOperation operation, Object id, GroupKeys group, Object mapKey, Object entity) {
        Segment segment = segmentOf(grouped ? group : id);
        synchronized (segment) {
            if (closed) throw new IllegalStateException("GameData is closed");
            HashMap<Object, PendingChange> changes = grouped ? segment.byGroup.computeIfAbsent(group, key -> new HashMap<>()) : segment.byId;
            PendingChange waiting = changes.get(id);
            if (waiting == null) {
                changes.put(id, new PendingChange(operation, id, group, mapKey, entity));
                segment.size++;
                return;
            }
            if (waiting.writing) {
                // Its write may reach the database at any moment, so an insert being written counts as a row to replace.
                DataOperation next = merge(waiting.operation == INSERT ? DELETE_INSERT : waiting.operation, operation);
                if (next == DELETE && waiting.operation == DELETE) return;
                changes.put(id, new PendingChange(next, id, group, mapKey, entity != null ? entity : waiting.entity));
                return;
            }
            DataOperation next = merge(waiting.operation, operation);
            if (next == null) {
                changes.remove(id);
                segment.size--;
                if (grouped && changes.isEmpty()) segment.byGroup.remove(group);
                return;
            }
            waiting.operation = next;
            waiting.mapKey = mapKey;
            if (entity != null) waiting.entity = entity;
        }
    }

    /** A copy of the change of an ungrouped row, waiting or being written, or null when it has none. */
    public PendingChange find(Object id) {
        Segment segment = segmentOf(id);
        synchronized (segment) {
            PendingChange change = segment.byId.get(id);
            return change == null ? null : change.copy();
        }
    }

    /** Copies of the changes of a group's rows, waiting or being written, by id; empty when it has none. */
    public Map<Object, PendingChange> findGroup(GroupKeys group) {
        Segment segment = segmentOf(group);
        synchronized (segment) {
            HashMap<Object, PendingChange> changes = segment.byGroup.get(group);
            if (changes == null) return Map.of();
            Map<Object, PendingChange> copies = new HashMap<>();
            for (PendingChange change : changes.values()) copies.put(change.id, change.copy());
            return copies;
        }
    }

    boolean isEmpty() {
        for (Segment segment : segments) synchronized (segment) { if (segment.size > 0) return false; }
        return true;
    }

    /**
     * Starts a round: every change waiting, which stays here while it is written. The caller sets
     * {@link PendingChange#finished} on those it wrote or gave up, then calls {@link #endRound} before taking again.
     */
    List<PendingChange> takeAll() {
        List<PendingChange> taken = new ArrayList<>();
        for (Segment segment : segments) {
            synchronized (segment) {
                if (segment.size == 0) continue;
                if (!grouped) take(segment.byId, taken);
                else for (HashMap<Object, PendingChange> changes : segment.byGroup.values()) take(changes, taken);
            }
        }
        return taken;
    }

    /**
     * Ends the round {@link #takeAll} started. The changes it finished leave; the others wait for the next round, an
     * insert among them as DELETE_INSERT, since a write that failed may have reached the database. A change that took
     * the place of one being written stays as it is.
     */
    void endRound() {
        for (Segment segment : segments) {
            synchronized (segment) {
                if (segment.size == 0) continue;
                if (!grouped) {
                    segment.size -= endRound(segment.byId);
                    continue;
                }
                for (Iterator<HashMap<Object, PendingChange>> groups = segment.byGroup.values().iterator(); groups.hasNext(); ) {
                    HashMap<Object, PendingChange> changes = groups.next();
                    segment.size -= endRound(changes);
                    if (changes.isEmpty()) groups.remove();
                }
            }
        }
    }

    /** Refuses every later change; what is waiting stays to be written. */
    void close() { closed = true; }

    /**
     * What a row with a {@code waiting} change that gets a {@code next} one is written as: the merged operation, or
     * null when nothing is left to write (a row inserted and deleted before either was written).
     */
    static DataOperation merge(DataOperation waiting, DataOperation next) {
        return switch (waiting) {
            case INSERT -> switch (next) {
                case UPDATE -> INSERT;
                case DELETE -> null;
                default -> throw invalidSequence(waiting, next);
            };
            case UPDATE -> switch (next) {
                case UPDATE, DELETE -> next;
                default -> throw invalidSequence(waiting, next);
            };
            case DELETE -> switch (next) {
                case INSERT -> DELETE_INSERT;
                case DELETE -> DELETE;
                default -> throw invalidSequence(waiting, next);
            };
            case DELETE_INSERT -> switch (next) {
                case UPDATE -> DELETE_INSERT;
                case DELETE -> DELETE;
                default -> throw invalidSequence(waiting, next);
            };
            case LOG_INSERT -> throw invalidSequence(waiting, next);
        };
    }

    private static IllegalStateException invalidSequence(DataOperation waiting, DataOperation next) {
        return new IllegalStateException("cannot " + next + " a row waiting to " + waiting);
    }

    private static void take(HashMap<Object, PendingChange> changes, List<PendingChange> taken) {
        for (PendingChange change : changes.values()) {
            change.writing = true;
            taken.add(change);
        }
    }

    /** Ends the round for these changes; returns how many left. */
    private static int endRound(HashMap<Object, PendingChange> changes) {
        int removed = 0;
        for (Iterator<PendingChange> taken = changes.values().iterator(); taken.hasNext(); ) {
            PendingChange change = taken.next();
            if (!change.writing) continue;
            if (change.finished) {
                taken.remove();
                removed++;
                continue;
            }
            change.writing = false;
            if (change.operation == INSERT) change.operation = DELETE_INSERT;
        }
        return removed;
    }

    private Segment segmentOf(Object key) {
        int hash = key.hashCode();
        return segments[(hash ^ (hash >>> 16)) & (SEGMENTS - 1)];
    }
}
