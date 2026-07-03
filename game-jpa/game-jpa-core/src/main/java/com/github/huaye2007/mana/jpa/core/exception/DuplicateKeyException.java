package com.github.huaye2007.mana.jpa.core.exception;

/**
 * 主键/唯一键冲突异常。
 */
public class DuplicateKeyException extends GameJpaException {

    private final String entityName;
    private final Object entityId;

    public DuplicateKeyException(String entityName, Object entityId) {
        super("Duplicate key on " + entityName + " [id=" + entityId + "]");
        this.entityName = entityName;
        this.entityId = entityId;
    }

    public DuplicateKeyException(String entityName, Object entityId, Throwable cause) {
        super("Duplicate key on " + entityName + " [id=" + entityId + "]", cause);
        this.entityName = entityName;
        this.entityId = entityId;
    }

    public String entityName() { return entityName; }
    public Object entityId() { return entityId; }
}
