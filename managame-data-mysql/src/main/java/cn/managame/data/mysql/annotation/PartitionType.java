package cn.managame.data.mysql.annotation;

/**
 * How a {@link PartitionKey} makes a table suffix: VALUE uses an integral or String field as it is; DAY, MONTH
 * and YEAR format a {@code long} of epoch milliseconds as {@code yyyyMMdd}, {@code yyyyMM} or {@code yyyy}, in the
 * builder's partition zone.
 */
public enum PartitionType { VALUE, DAY, MONTH, YEAR }
