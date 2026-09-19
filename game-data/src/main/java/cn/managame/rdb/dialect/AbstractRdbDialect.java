package cn.managame.rdb.dialect;

import cn.managame.core.DataException;
import cn.managame.core.metadata.EntityMetadata;

import cn.managame.rdb.RdbDialect;

abstract class AbstractRdbDialect implements RdbDialect {
    @Override
    public String createTableSql(EntityMetadata<?> metadata) {
        return RdbDialect.super.createTableSql(metadata);
    }

    @Override
    public String createTableSql(EntityMetadata<?> metadata, String physicalName) {
        return RdbDialect.super.createTableSql(metadata, physicalName);
    }

    protected final void validateIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank() || !identifier.matches("[A-Za-z0-9_]+")) {
            throw new DataException("Unsafe RDB identifier: " + identifier);
        }
    }
}
