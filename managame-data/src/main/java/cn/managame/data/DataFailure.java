package cn.managame.data;

import cn.managame.FrameworkErrorCodes;

/**
 * A change or log the framework gave up saving: {@code errorCode} is {@link FrameworkErrorCodes#DATA_SAVE_FAILED}
 * for entities and {@link FrameworkErrorCodes#DATA_LOG_SAVE_FAILED} for logs. {@code value} is the entity (its
 * {@code @Id} when a delete never had it) or the log, still shared with the application: copy what you need
 * before handing it to another thread.
 */
public record DataFailure(int errorCode, Class<?> entityType, DataOperation operation, Object value, Throwable cause) {}
