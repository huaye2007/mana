package cn.managame.data;

/**
 * What a save does to a row. DELETE_INSERT replaces a row in one step: a delete followed by an insert of the same
 * {@code @Id} before they were saved, or an insert whose outcome a failure left unknown, retried.
 */
public enum DataOperation { INSERT, UPDATE, DELETE, DELETE_INSERT, LOG_INSERT }
