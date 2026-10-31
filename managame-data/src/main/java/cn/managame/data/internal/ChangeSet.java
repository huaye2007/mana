package cn.managame.data.internal;

import static cn.managame.data.DataOperation.DELETE;
import static cn.managame.data.DataOperation.DELETE_INSERT;
import static cn.managame.data.DataOperation.INSERT;
import static cn.managame.data.DataOperation.UPDATE;

import cn.managame.data.DataOperation;
import cn.managame.data.GroupKeys;
import cn.managame.data.spi.EntityMapper;
import cn.managame.data.spi.EntityType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One entity type's changes not yet written, at most one per row, and the mapper that writes them. A new change of a
 * row merges into the one waiting, so a row changed many times between rounds is written once. A round takes
 * everything waiting at once and puts back what it could not write, which merges in under the changes made
 * meanwhile. Until then the taken changes stay visible to {@link #find} and {@link #findGroup}, so a load never
 * reads a row older than its last change.
 *
 * <p>Rows are spread over segments by id, or by group for a grouped type, each with its own lock, so business threads
 * adding changes rarely wait for each other. Only the one thread writing the type takes and puts back.
 *
 * <p>Not API: it may change in any release.
 */
public final class ChangeSet {
    private static final int SEGMENTS = 64;
    final EntityType type;
    final EntityMapper mapper;
    private final boolean grouped;
    private final Segment[] segments = new Segment[SEGMENTS];
    private volatile boolean closed;

    /**
     * Changes by id, or for a grouped type changes by id within maps by group: {@code waiting} holds those not yet
     * taken, {@code writing} those the round in progress took. Padded to a cache line of its own, so threads locking
     * neighbouring segments do not slow each other down.
     */
    @SuppressWarnings("unused")
    private static final class Segment {
        HashMap<Object, Object> waiting = new HashMap<>(), writing = new HashMap<>();
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

    /**
     * Adds a change of a row, merged into the one waiting. Throws IllegalStateException, changing nothing, when the
     * sequence is invalid (an insert of a row waiting to be inserted or updated, an update of a deleted row) or the
     * change set is closed.
     */
    public void add(DataOperation operation, Object id, GroupKeys group, Object mapKey, Object entity) {
        Segment segment = segmentOf(grouped ? group : id);
        synchronized (segment) {
            if (closed) throw new IllegalStateException("GameData is closed");
            Map<Object, PendingChange> changes = changesOf(segment.waiting, group, true);
            PendingChange waiting = changes.get(id);
            if (waiting == null) {
                changes.put(id, new PendingChange(operation, id, group, mapKey, entity));
                segment.size++;
                return;
            }
            DataOperation merged = merge(waiting.operation, operation);
            if (merged == null) {
                removeChange(segment, changes, group, id);
                return;
            }
            waiting.operation = merged;
            if (entity != null) waiting.entity = entity;
        }
    }

    /** A copy of the newest change of an ungrouped row, waiting or being written, or null when it has none. */
    public PendingChange find(Object id) {
        Segment segment = segmentOf(id);
        synchronized (segment) {
            PendingChange change = (PendingChange) segment.waiting.get(id);
            if (change == null) change = (PendingChange) segment.writing.get(id);
            return change == null ? null : change.copy();
        }
    }

    /** Copies of the newest change of each row of a group that has one, waiting or being written. */
    public List<PendingChange> findGroup(GroupKeys group) {
        Segment segment = segmentOf(group);
        synchronized (segment) {
            Map<Object, PendingChange> writing = changesOf(segment.writing, group, false), waiting = changesOf(segment.waiting, group, false);
            if (writing == null && waiting == null) return List.of();
            Map<Object, PendingChange> newest = new HashMap<>();
            if (writing != null) newest.putAll(writing);
            if (waiting != null) newest.putAll(waiting);
            List<PendingChange> copies = new ArrayList<>(newest.size());
            for (PendingChange change : newest.values()) copies.add(change.copy());
            return copies;
        }
    }

    boolean isEmpty() {
        for (Segment segment : segments) synchronized (segment) { if (segment.size > 0) return false; }
        return true;
    }

    /** Takes everything waiting. The caller hands the result back with {@link #putBack} before taking again. */
    List<PendingChange> takeAll() {
        List<PendingChange> taken = new ArrayList<>();
        for (Segment segment : segments) {
            synchronized (segment) {
                if (segment.size == 0) continue;
                HashMap<Object, Object> changes = segment.waiting;
                segment.waiting = segment.writing;
                segment.writing = changes;
                segment.size = 0;
                addAllChanges(changes, taken);
            }
        }
        return taken;
    }

    /**
     * Ends the writing of what {@link #takeAll} returned. Each of {@code unwritten} merges in under the change its row
     * got meanwhile; an insert among them comes back as DELETE_INSERT, since the failure may have left it written.
     * Returns those that cannot merge (the row was inserted again meanwhile), which are lost.
     */
    List<PendingChange> putBack(List<PendingChange> unwritten) {
        List<PendingChange> conflicts = new ArrayList<>(0);
        List<List<PendingChange>> bySegment = new ArrayList<>(SEGMENTS);
        for (int i = 0; i < SEGMENTS; i++) bySegment.add(new ArrayList<>(0));
        for (PendingChange change : unwritten) bySegment.get(segmentIndex(grouped ? change.group : change.id)).add(change);
        for (int i = 0; i < SEGMENTS; i++) {
            Segment segment = segments[i];
            synchronized (segment) {
                for (PendingChange change : bySegment.get(i)) if (!putBackOne(segment, change)) conflicts.add(change);
                segment.writing.clear();
            }
        }
        return conflicts;
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

    /** Puts back one unwritten change under the row's later one, if any; false when the two cannot be merged. */
    private boolean putBackOne(Segment segment, PendingChange unwritten) {
        DataOperation unwrittenOperation = unwritten.operation == INSERT ? DELETE_INSERT : unwritten.operation;
        Map<Object, PendingChange> changes = changesOf(segment.waiting, unwritten.group, true);
        PendingChange later = changes.get(unwritten.id);
        if (later == null) {
            unwritten.operation = unwrittenOperation;
            changes.put(unwritten.id, unwritten);
            segment.size++;
            return true;
        }
        DataOperation merged;
        try {
            merged = later.operation == DELETE_INSERT
                    ? merge(merge(unwrittenOperation, DELETE), INSERT) : merge(unwrittenOperation, later.operation);
        } catch (IllegalStateException e) {
            return false;
        }
        if (merged == null) removeChange(segment, changes, unwritten.group, unwritten.id);
        else later.operation = merged;
        return true;
    }

    private void removeChange(Segment segment, Map<Object, PendingChange> changes, GroupKeys group, Object id) {
        changes.remove(id);
        segment.size--;
        if (grouped && changes.isEmpty()) segment.waiting.remove(group);
    }

    @SuppressWarnings("unchecked")
    private Map<Object, PendingChange> changesOf(HashMap<Object, Object> map, GroupKeys group, boolean create) {
        if (!grouped) return (Map<Object, PendingChange>) (Map<?, ?>) map;
        Map<Object, PendingChange> changes = (Map<Object, PendingChange>) map.get(group);
        if (changes == null && create) map.put(group, changes = new HashMap<>());
        return changes;
    }

    @SuppressWarnings("unchecked")
    private void addAllChanges(HashMap<Object, Object> map, List<PendingChange> into) {
        if (!grouped) {
            for (Object change : map.values()) into.add((PendingChange) change);
            return;
        }
        for (Object changes : map.values()) into.addAll(((Map<Object, PendingChange>) changes).values());
    }

    private Segment segmentOf(Object key) { return segments[segmentIndex(key)]; }

    private static int segmentIndex(Object key) {
        int hash = key.hashCode();
        return (hash ^ (hash >>> 16)) & (SEGMENTS - 1);
    }
}
