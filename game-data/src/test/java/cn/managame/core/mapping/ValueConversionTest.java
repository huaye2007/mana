package cn.managame.core.mapping;

import cn.managame.core.metadata.EntityMetadata;

import cn.managame.annotation.Id;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValueConversionTest {
    private final DefaultValueConverter converter = new DefaultValueConverter();

    @Test void jdbcDecimalPreservesLargePositiveAndNegativeIntegers() {
        for (String value : List.of("123456789012345678901234567890", "-99999999999999999999999999999999999999999999999999999999999999999999")) {
            BigInteger integer = new BigInteger(value);
            assertEquals(new BigDecimal(integer), new cn.managame.rdb.RdbValueConverter().toStore(integer, BigInteger.class));
            assertEquals(integer, converter.fromStore(new BigDecimal(value), BigInteger.class));
        }
    }

    @Test void decimalConversionNeverPassesThroughDouble() {
        BigInteger integer = new BigInteger("123456789012345678901234567890");
        assertEquals(new BigDecimal(integer), converter.fromStore(integer, BigDecimal.class));
        assertEquals(new BigDecimal("9223372036854775807"), converter.fromStore(Long.MAX_VALUE, BigDecimal.class));
        assertEquals(new BigDecimal("0.1"), converter.fromStore(0.1f, BigDecimal.class));
        BigDecimal decimal = new BigDecimal("12345678901234567890.1234567890");
        assertSame(decimal, converter.fromStore(decimal, BigDecimal.class));
    }

    @Test void fractionalIntegerConversionFailsInsteadOfSilentlyTruncating() {
        assertThrows(ArithmeticException.class, () -> converter.fromStore(new BigDecimal("100.25"), BigInteger.class));
        assertEquals(BigInteger.valueOf(100), converter.fromStore(new BigDecimal("100.000"), BigInteger.class));
    }

    public static class Numbers {
        @Id public long id;
        public BigInteger total;
        public BigDecimal price;
        public byte[] bytes;
        public Numbers() { }
    }

    @Test void jdbcEntityMappingRestoresDecimalAndBinaryFields() {
        var mapper = new EntityMapper(new cn.managame.rdb.RdbValueConverter());
        var metadata = EntityMetadata.inspect(Numbers.class);
        var original = new Numbers();
        original.id = 1;
        original.total = new BigInteger("123456789012345678901234567890");
        original.price = new BigDecimal("12345678901234567890.1234567890");
        original.bytes = new byte[] {0, 1, -1};
        Map<String, Object> row = new HashMap<>();
        for (var property : metadata.properties()) row.put(property.rdbName(), mapper.toStore(property.get(original), property));
        Numbers restored = mapper.fromRdb(metadata, row);
        assertEquals(original.total, restored.total);
        assertEquals(original.price, restored.price);
        assertArrayEquals(original.bytes, restored.bytes);
        row.put("total", null); row.put("bytes", null);
        assertNull(mapper.fromRdb(metadata, row).total);
        assertNull(mapper.fromRdb(metadata, row).bytes);
    }
}
