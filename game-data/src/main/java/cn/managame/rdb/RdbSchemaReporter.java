package cn.managame.rdb;

/** Receives schema differences that are intentionally not auto-migrated. */
@FunctionalInterface
public interface RdbSchemaReporter {
    void report(RdbSchemaIssue issue);

    static RdbSchemaReporter stderr() {
        return issue -> System.err.println("[game-data][schema] " + issue);
    }

    static RdbSchemaReporter noop() {
        return issue -> { };
    }
}
