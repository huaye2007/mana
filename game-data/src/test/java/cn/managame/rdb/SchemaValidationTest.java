package cn.managame.rdb;

import cn.managame.annotation.*;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.rdb.dialect.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchemaValidationTest {
    public static class Row {
        @Id public long id;
        public LocalDate day;
        public LocalDateTime created;
        public LocalTime time;
        public BigDecimal amount;
    }
    static final EntityMetadata<Row> META = EntityMetadata.inspect(Row.class);
    static class Jdbc {
        final List<Map<String,Object>> columns = new ArrayList<>(List.of(
                column("id","BIGINT",19,null,DatabaseMetaData.columnNoNulls),
                column("day","DATE",10,null,DatabaseMetaData.columnNullable),
                column("created","DATETIME",26,6,DatabaseMetaData.columnNullable),
                column("time","TIME",15,6,DatabaseMetaData.columnNullable),
                column("amount","DECIMAL",38,10,DatabaseMetaData.columnNullable)));
        List<String> primary = List.of("id");
        List<RdbSchemaIssue> inspect(RdbDialect dialect) {
            DatabaseMetaData metadata = proxy(DatabaseMetaData.class,(p,m,a)->switch(m.getName()) {
                case "getColumns" -> rows(columns);
                case "getIndexInfo" -> rows(List.of());
                case "getPrimaryKeys" -> rows(primary.stream().map(name -> Map.<String,Object>of("COLUMN_NAME", name)).toList());
                default -> throw new AssertionError(m);
            });
            DataSource source = proxy(DataSource.class,(p,m,a)-> {
                if (!m.getName().equals("getConnection")) throw new AssertionError(m);
                return proxy(Connection.class,(c,method,args)->switch(method.getName()) {
                    case "getMetaData" -> metadata;
                    case "getCatalog" -> "game";
                    case "close" -> null;
                    default -> throw new AssertionError("Inspection must not execute DDL: " + method);
                });
            });
            return new RdbSchemaManager(source,dialect).inspect(META);
        }
    }
    @Test void matchingDateTimeAndDecimalSchemaDoesNotReportFalseDifferences() {
        assertTrue(new Jdbc().inspect(new MySqlDialect()).isEmpty());
        var pg = new Jdbc(); pg.columns.getFirst().put("TYPE_NAME", "int8");
        pg.columns.get(2).put("TYPE_NAME", "timestamp");pg.columns.get(4).put("TYPE_NAME", "numeric");
        assertTrue(pg.inspect(new PostgreSqlDialect()).isEmpty());
    }
    @Test void dateAndTimestampAreNotTreatedAsInterchangeable() {
        var jdbc=new Jdbc(); jdbc.columns.get(2).put("TYPE_NAME","DATE");
        var issues=jdbc.inspect(new MySqlDialect());
        assertEquals(1,issues.size());assertEquals(RdbSchemaIssue.Kind.COLUMN_TYPE,issues.getFirst().kind());
    }
    @Test void absentWrongAndCompositePrimaryKeysAreReportedWithoutAlteringTheTable() {
        for (List<String> key : List.of(List.<String>of(), List.of("day"), List.of("id","day"))) {
            var jdbc=new Jdbc();jdbc.primary=key;
            var issues=jdbc.inspect(new MySqlDialect());
            assertEquals(1,issues.size());assertEquals(RdbSchemaIssue.Kind.PRIMARY_KEY,issues.getFirst().kind());
        }
    }
    @Test void nullablePrimaryIdIsReported() {
        var jdbc=new Jdbc();jdbc.columns.getFirst().put("NULLABLE",DatabaseMetaData.columnNullable);
        var issues=jdbc.inspect(new MySqlDialect());
        assertEquals(1,issues.size());assertEquals(RdbSchemaIssue.Kind.COLUMN_NULLABILITY,issues.getFirst().kind());
    }
    @Test void insufficientFractionOrIntegerCapacityIsReported() {
        for (int[] shape : List.of(new int[]{38,5},new int[]{38,15},new int[]{30,10})) {
            var jdbc=new Jdbc();jdbc.columns.get(4).put("COLUMN_SIZE",shape[0]);jdbc.columns.get(4).put("DECIMAL_DIGITS",shape[1]);
            var issues=jdbc.inspect(new MySqlDialect());
            assertEquals(1,issues.size());assertEquals(RdbSchemaIssue.Kind.COLUMN_PRECISION,issues.getFirst().kind());
        }
        var wider=new Jdbc();wider.columns.get(4).put("COLUMN_SIZE",48);wider.columns.get(4).put("DECIMAL_DIGITS",15);
        assertTrue(wider.inspect(new MySqlDialect()).isEmpty());
        wider.columns.get(4).remove("DECIMAL_DIGITS");
        assertTrue(wider.inspect(new MySqlDialect()).isEmpty());
    }
    static Map<String,Object> column(String name,String type,int size,Integer scale,int nullable) {
        var row=new HashMap<String,Object>();row.put("COLUMN_NAME",name);row.put("TYPE_NAME",type);row.put("COLUMN_SIZE",size);
        row.put("DECIMAL_DIGITS",scale);row.put("NULLABLE",nullable);return row;
    }
    static ResultSet rows(List<Map<String,Object>> rows) {
        int[] position={-1};
        return proxy(ResultSet.class,(p,m,a)->switch(m.getName()) {
            case "next" -> ++position[0]<rows.size();
            case "getString", "getInt", "getObject" -> rows.get(position[0]).get(a[0]);
            case "close" -> null;
            default -> throw new AssertionError(m);
        });
    }
    static <T> T proxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
}
