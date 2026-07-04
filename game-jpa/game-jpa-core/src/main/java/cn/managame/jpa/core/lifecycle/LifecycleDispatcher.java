package cn.managame.jpa.core.lifecycle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 生命周期事件分发器。
 * 管理所有注册的 LifecycleListener 并按顺序分发事件。
 * <p>
 * Listener 抛出异常时会中断当前 Repository 操作并向调用方传播。
 */
public class LifecycleDispatcher {

    private final List<LifecycleListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(LifecycleListener listener) {
        listeners.add(listener);
    }

    public boolean hasListeners() {
        return !listeners.isEmpty();
    }

    public void fireBeforeInsert(Object entity) {
        for (LifecycleListener listener : listeners) {
            listener.beforeInsert(entity);
        }
    }

    public void fireAfterInsert(Object entity) {
        for (LifecycleListener listener : listeners) {
            listener.afterInsert(entity);
        }
    }

    public void fireBeforeUpdate(Object entity) {
        for (LifecycleListener listener : listeners) {
            listener.beforeUpdate(entity);
        }
    }

    public void fireAfterUpdate(Object entity) {
        for (LifecycleListener listener : listeners) {
            listener.afterUpdate(entity);
        }
    }

    public void fireAfterLoad(Object entity) {
        for (LifecycleListener listener : listeners) {
            listener.afterLoad(entity);
        }
    }

    public void fireBeforeDelete(Object entity) {
        for (LifecycleListener listener : listeners) {
            listener.beforeDelete(entity);
        }
    }

    public void fireAfterDelete(Object entity) {
        for (LifecycleListener listener : listeners) {
            listener.afterDelete(entity);
        }
    }
}
