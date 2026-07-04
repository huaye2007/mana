package cn.managame.jpa.rdb.metadata;

import java.util.List;

/**
 * 索引元数据。
 */
public record RdbIndexMetadata(String name, List<String> columns, boolean unique) {
}
