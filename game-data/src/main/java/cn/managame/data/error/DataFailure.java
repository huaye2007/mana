package cn.managame.data.error;

import java.util.List;

/** Batch references are borrowed during the callback. Serialize before handing to another thread. */
public record DataFailure(int errorCode, Class<?> entityType, DataOperation operation,
                          List<?> batch, Throwable cause, int attempt) {}
