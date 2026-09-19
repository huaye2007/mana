package cn.managame.annotation;

/**
 * Explicit persistence shape for an RDB column. AUTO keeps the type inferred from the Java field.
 * TEXT/JSON/BINARY are also useful to make the intended storage contract explicit across RDB dialects.
 */
public enum ColumnType {
    AUTO,
    STRING,
    TEXT,
    JSON,
    BINARY
}
