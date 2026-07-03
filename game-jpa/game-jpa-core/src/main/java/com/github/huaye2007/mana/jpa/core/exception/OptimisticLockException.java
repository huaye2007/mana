package com.github.huaye2007.mana.jpa.core.exception;

/**
 * 乐观锁冲突异常。
 * 当 update 时 version 不匹配时抛出。
 */
public class OptimisticLockException extends GameJpaException {

    private final String entityName;
    private final Object entityId;

    public OptimisticLockException(String entityName, Object entityId) {
        super("Optimistic lock conflict on " + entityName + " [id=" + entityId + "]");
        this.entityName = entityName;
        this.entityId = entityId;
    }

    public String entityName() { return entityName; }
    public Object entityId() { return entityId; }
}
