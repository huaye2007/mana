package cn.managame.core.mapping;

import cn.managame.docdb.mongodb.MongoValueConverter;
import cn.managame.rdb.RdbDataAccess;
import cn.managame.rdb.RdbValueConverter;
import cn.managame.rdb.dialect.MySqlDialect;
import java.lang.reflect.Proxy;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.bson.types.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackendValueConversionTest {
    @Test void coreLeavesBackendSpecificStorageRepresentationsToAdapters() {
        var converter = new DefaultValueConverter();
        for (Object value : List.of(Instant.now(), LocalDate.now(), LocalDateTime.now(), BigInteger.TEN, BigDecimal.TEN)) {
            assertSame(value, converter.toStore(value, value.getClass()));
        }
    }

    @Test void rdbDefaultsUseJdbcTypesAndPreserveTimestampNanoseconds() {
        DataSource source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class}, (p, m, a) -> { throw new AssertionError(m); });
        var mapper = new RdbDataAccess(source, new MySqlDialect()).mapper();
        var instant = Instant.parse("2026-09-19T12:34:56.123456789Z");
        Object stored = mapper.toStore(instant, Instant.class);
        assertInstanceOf(Timestamp.class, stored);
        assertEquals(instant, mapper.fromStore(stored, Instant.class));
        var date = LocalDate.of(2026, 9, 19);
        assertInstanceOf(java.sql.Date.class, mapper.toStore(date, LocalDate.class));
        assertEquals(date, mapper.fromStore(mapper.toStore(date, LocalDate.class), LocalDate.class));
        var time = date.atTime(12, 34, 56, 123456789);
        assertEquals(time, mapper.fromStore(mapper.toStore(time, LocalDateTime.class), LocalDateTime.class));
    }

    @Test void mongoDatesKeepExistingEpochValuesWithoutJdbcObjects() {
        var converter = new MongoValueConverter();
        TimeZone previous = TimeZone.getDefault();
        try {
            for (String zone : List.of("UTC", "Asia/Shanghai", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                for (LocalDate date : List.of(LocalDate.of(2026, 9, 19), LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1))) {
                    Date stored = (Date) converter.toStore(date, LocalDate.class);
                    assertEquals(Date.class, stored.getClass());
                    assertEquals(java.sql.Date.valueOf(date).getTime(), stored.getTime());
                    assertEquals(date, converter.fromStore(stored, LocalDate.class));
                }
                var time = LocalDateTime.of(2026, 11, 1, 1, 30, 0, 123000000);
                Date stored = (Date) converter.toStore(time, LocalDateTime.class);
                assertEquals(Timestamp.valueOf(time).getTime(), stored.getTime(), zone);
                assertEquals(time, converter.fromStore(stored, LocalDateTime.class));
                var instant = Instant.parse("2026-09-19T12:34:56.123Z");
                assertEquals(instant, converter.fromStore(converter.toStore(instant, Instant.class), Instant.class));
            }
        } finally { TimeZone.setDefault(previous); }
    }

    @Test void decimalAndBinaryRepresentationsAreBackendSpecificAndExact() {
        var rdb = new RdbValueConverter(); var mongo = new MongoValueConverter();
        var integer = new BigInteger("123456789012345678901234567890");
        assertInstanceOf(BigDecimal.class, rdb.toStore(integer, BigInteger.class));
        assertInstanceOf(Decimal128.class, mongo.toStore(integer, BigInteger.class));
        assertEquals(integer, mongo.fromStore(mongo.toStore(integer, BigInteger.class), BigInteger.class));
        assertThrows(ArithmeticException.class, () -> mongo.fromStore(new Decimal128(new BigDecimal("1.5")), BigInteger.class));
        byte[] bytes = {0, -1, 12};
        assertArrayEquals(bytes, (byte[]) mongo.fromStore(new Binary(bytes), byte[].class));
        assertNull(mongo.fromStore(null, LocalDate.class));
        assertNull(rdb.fromStore(null, LocalDate.class));
        assertEquals(0L, mongo.fromStore(null, long.class));
        assertEquals(0L, rdb.fromStore(null, long.class));
    }
}
