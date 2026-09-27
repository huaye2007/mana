package cn.managame.data.mysql;

import cn.managame.data.codec.*;
import cn.managame.data.meta.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Internal log mapping bridge. Does not initialize schema or use EntityMapper. */
public final class MysqlLogWriter {
    private final Class<?> type;
    private final MysqlAccess access;
    private final String baseTable;
    private final List<MysqlFieldMeta> fields;
    private final FieldAccessor partition;
    private final PartitionType partitionType;
    private final ZoneId zone;
    public MysqlLogWriter(Class<?> type, MysqlAccess access, JsonCodec json, BinaryCodec binary, ZoneId zone) {
        this.type = type; this.access = access; this.zone = zone;
        Table table = type.getAnnotation(Table.class);
        if (table == null) throw new IllegalArgumentException("Log requires @Table: " + type);
        baseTable = SqlNames.identifier(table.name());
        var source = EntityMeta.fieldsOf(type);
        fields = MysqlEntityMeta.fields(source, Set.of(), json, binary);
        var partitions = source.stream().filter(f -> f.isAnnotationPresent(PartitionKey.class)).toList();
        if (partitions.size() > 1) throw new IllegalArgumentException("Log allows at most one PartitionKey");
        partition = partitions.isEmpty() ? null : new FieldAccessor(partitions.getFirst());
        partitionType = partition == null ? PartitionType.VALUE : partition.field().getAnnotation(PartitionKey.class).type();
        if (partition != null) {
            Class<?> p = EntityMeta.boxed(partition.type());
            boolean valid = partitionType == PartitionType.VALUE
                    ? p == String.class || p == Long.class || p == Integer.class || p == Short.class || p == Byte.class
                    : p == Long.class;
            if (!valid) throw new IllegalArgumentException("Time partitions require epoch-millisecond long; VALUE requires integral/String");
        }
    }
    public Class<?> logType() { return type; }
    public void validate(Object log) {
        if (!type.isInstance(log)) throw new IllegalArgumentException("Wrong log type");
        table(log);
    }
    public String table(Object log) {
        if (partition == null) return baseTable;
        Object value = Objects.requireNonNull(partition.get(log), "Null PartitionKey");
        String suffix;
        if (partitionType == PartitionType.VALUE) suffix = value.toString();
        else {
            var date = Instant.ofEpochMilli((Long) value).atZone(zone);
            suffix = date.format(DateTimeFormatter.ofPattern(switch (partitionType) {
                case DAY -> "uuuuMMdd"; case MONTH -> "uuuuMM"; case YEAR -> "uuuu"; default -> throw new AssertionError();
            }, Locale.ROOT));
        }
        if (!suffix.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Unsafe partition suffix: " + suffix);
        return SqlNames.identifier(baseTable + "_" + suffix);
    }
    public void insert(String table, List<?> logs) {
        access.batchUpdate(MysqlEntityMeta.insertSql(table, fields),
                logs.stream().map(log -> MysqlEntityMeta.arguments(fields, log)).toList());
    }
}
