package cn.managame.ecs.change;

/**
 * Kind of a component-level change exposed to synchronization consumers.
 */
public enum ComponentChangeKind {

    /**
     * The component did not exist and is now attached.
     */
    ADDED,

    /**
     * The component instance stored under the same type was replaced.
     */
    REPLACED,

    /**
     * The component was detached.
     */
    REMOVED,

    /**
     * One or more fields of a mutable component changed.
     */
    FIELDS_UPDATED
}
