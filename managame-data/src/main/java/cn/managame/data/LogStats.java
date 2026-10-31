package cn.managame.data;

/**
 * One log repository as {@link GameData#stats()} read it. {@code queued} is how many logs wait in its queue, not
 * counting those the round in progress took. Since GameData was built, {@code written} logs were written,
 * {@code refused} were refused by a full queue, {@code failures} writes failed (a batch, or a single log once a batch is
 * split, each counting once, whether tried again or dropped) and {@code dropped} logs taken by a round were handed to
 * the DataErrorHandler; refused logs go to it too but are not counted there.
 */
public record LogStats(Class<?> logType, int queued, long written, long refused, long failures, long dropped) {}
