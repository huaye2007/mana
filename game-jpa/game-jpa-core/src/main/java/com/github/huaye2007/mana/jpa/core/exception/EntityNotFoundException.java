package com.github.huaye2007.mana.jpa.core.exception;

/**
 * 实体未找到异常。
 */
public class EntityNotFoundException extends GameJpaException {

    private final String entityName;
    private final Object entityId;

    public EntityNotFoundException(String entityName, Object entityId) {
        super("Entity not found: " + entityName + " [id=" + entityId + "]");
        this.entityName = entityName;
        this.entityId = entityId;
    }

    public String entityName() { return entityName; }
    public Object entityId() { return entityId; }
}
