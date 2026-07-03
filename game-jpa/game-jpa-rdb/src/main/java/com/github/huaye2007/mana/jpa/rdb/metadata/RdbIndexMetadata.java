package com.github.huaye2007.mana.jpa.rdb.metadata;

import java.util.List;

/**
 * 索引元数据。
 */
public record RdbIndexMetadata(String name, List<String> columns, boolean unique) {
}
