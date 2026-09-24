package cn.managame.core.write;

public enum WriteType {
    INSERT,
    UPDATE,
    DELETE,
    /** Buffer-only state; expanded to ordered DELETE then INSERT before calling DataAccess. */
    DELETE_INSERT,
    DELETE_GROUP
}
