package cn.managame.data.mysql;
/**
 * DEFAULT picks the column from the field: a scalar, String, char or byte[] gets its own SQL type, and anything
 * else — collections, maps, enums, objects — is stored as JSON. The others force one representation.
 */
public enum ColumnType { DEFAULT, JSON, BINARY, TEXT }
