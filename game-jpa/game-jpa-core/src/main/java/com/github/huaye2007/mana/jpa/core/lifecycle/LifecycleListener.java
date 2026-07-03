package com.github.huaye2007.mana.jpa.core.lifecycle;

/**
 * 实体生命周期监听器。
 * 在 CRUD 操作前后触发回调。
 */
public interface LifecycleListener {

    default void beforeInsert(Object entity) {}

    default void afterInsert(Object entity) {}

    default void beforeUpdate(Object entity) {}

    default void afterUpdate(Object entity) {}

    default void afterLoad(Object entity) {}

    default void beforeDelete(Object entity) {}

    default void afterDelete(Object entity) {}
}
