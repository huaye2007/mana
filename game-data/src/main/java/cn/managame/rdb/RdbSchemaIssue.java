package cn.managame.rdb;

/** Non-destructive schema difference discovered during safe schema alignment. */
public record RdbSchemaIssue(
        Kind kind,
        String table,
        String objectName,
        String expected,
        String actual) {

    public enum Kind {
        COLUMN_TYPE,
        COLUMN_LENGTH,
        COLUMN_PRECISION,
        COLUMN_NULLABILITY,
        PRIMARY_KEY,
        INDEX_DEFINITION
    }

    @Override
    public String toString() {
        return kind + " table=" + table + ", object=" + objectName
                + ", expected=" + expected + ", actual=" + actual;
    }
}
