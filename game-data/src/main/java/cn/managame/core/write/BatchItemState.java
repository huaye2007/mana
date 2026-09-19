package cn.managame.core.write;

/** Per-operation result for a homogeneous physical-table batch. */
public enum BatchItemState {
    SUCCESS,
    FAILED,
    UNEXECUTED,
    UNKNOWN
}
