package cn.managame.jpa.core.write;

import java.io.Serial;
import java.io.Serializable;

/**
 * 异步写任务。
 * <p>
 * 支持同 ID 操作合并：同一实体的多次操作只保留最终状态。
 */
public class WriteTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Op { SAVE, DELETE }

    private final String entityName;
    private Op op;
    private Object entity;
    private final Object id;
    private int retryCount;

    public WriteTask(String entityName, Op op, Object entity, Object id) {
        this(entityName, op, entity, id, 0);
    }

    private WriteTask(String entityName, Op op, Object entity, Object id, int retryCount) {
        this.entityName = entityName;
        this.op = op;
        this.entity = entity;
        this.id = id;
        this.retryCount = retryCount;
    }

    public String entityName() { return entityName; }
    public Op op() { return op; }
    public Object entity() { return entity; }
    public Object id() { return id; }
    public int retryCount() { return retryCount; }
    public void incrementRetry() { retryCount++; }

    public WriteTask copy() {
        return new WriteTask(entityName, op, entity, id, retryCount);
    }

    /**
     * 将新操作合并到当前任务。
     *
     * @return true 保留该任务，false 操作抵消应移除
     */
    public boolean merge(Op newOp, Object newEntity) {
        switch (this.op) {
            case SAVE -> {
                switch (newOp) {
                    case SAVE -> { this.entity = newEntity; return true; }
                    case DELETE -> { this.op = Op.DELETE; this.entity = newEntity; return true; }
                }
            }
            case DELETE -> {
                switch (newOp) {
                    case SAVE -> { this.op = Op.SAVE; this.entity = newEntity; return true; }
                    case DELETE -> {
                        if (this.entity == null && newEntity != null) {
                            this.entity = newEntity;
                        }
                        return true;
                    }
                }
            }
        }
        this.op = newOp;
        this.entity = newEntity;
        return true;
    }
}
