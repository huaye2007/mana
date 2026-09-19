package cn.managame.docdb.mongodb;

import cn.managame.core.access.Query;
import cn.managame.core.mapping.EntityMapper;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.write.WriteOperation;

import cn.managame.annotation.Id;
import cn.managame.codec.jackson.JacksonJsonCodec;
import com.mongodb.MongoClientSettings;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.*;
import com.mongodb.client.model.InsertOneModel;
import org.bson.*;
import org.bson.codecs.*;
import org.bson.types.Binary;
import java.lang.reflect.*;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MongoValueRoundTripTest {
    public static class Row {
        @Id public long id;
        public byte[] payload;
        public BigInteger total;
        public BigDecimal price;
        public Row() { }
    }
    public static class JsonRow {
        @Id public long id;
        public byte[] payload;
        public List<String> tags;
        public JsonRow() { }
    }

    public static class DatedRow {
        @Id public long id;
        public LocalDate day;
    }

    enum Kind { GOLD }
    public static class Scalars {
        @Id public long id;
        public byte tiny;
        public short small;
        public int count;
        public boolean active;
        public float ratio;
        public double price;
        public char code;
        public UUID reference;
        public Kind kind;
        public java.time.Instant instant;
        public java.time.LocalDateTime time;
    }
    @Test void bsonScalarRepresentationsRoundTripIncludingEnumUuidCharacterAndTimes() {
        var store = new BsonStore();
        try (var access = new MongoDataAccess(store.client(), "test")) {
            var metadata = EntityMetadata.inspect(Scalars.class); access.validateMapping(metadata);
            var row = new Scalars(); row.id=1; row.tiny=-7; row.small=1234; row.count=123456;
            row.active=true; row.ratio=1.5f; row.price=12.25; row.code='X'; row.reference=UUID.randomUUID(); row.kind=Kind.GOLD;
            row.instant=java.time.Instant.parse("2026-09-19T12:00:00.123Z");row.time=java.time.LocalDateTime.of(2026,9,19,12,0,0,123000000);
            access.applyBatch(List.of(WriteOperation.insert(metadata,row,1L,null)));
            var restored=access.findById(metadata,1L).orElseThrow();
            assertEquals(row.tiny,restored.tiny);assertEquals(row.small,restored.small);assertEquals(row.count,restored.count);
            assertTrue(restored.active);assertEquals(row.ratio,restored.ratio);assertEquals(row.price,restored.price);
            assertEquals(row.code,restored.code);assertEquals(row.reference,restored.reference);assertEquals(row.kind,restored.kind);
            assertEquals(row.instant,restored.instant);assertEquals(row.time,restored.time);
            row.reference=null;row.kind=null;row.instant=null;row.time=null;
            access.applyBatch(List.of(WriteOperation.insert(metadata,row,1L,null)));
            restored=access.findById(metadata,1L).orElseThrow();
            assertNull(restored.reference);assertNull(restored.kind);assertNull(restored.instant);assertNull(restored.time);
        }
    }

    @Test void localDateRoundTripsInWriterZoneThroughAllMongoReadPaths() {
        TimeZone previous = TimeZone.getDefault();
        try {
            for (String zone : List.of("UTC", "Asia/Shanghai", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                for (boolean custom : List.of(false, true)) {
                    var mapper = new EntityMapper(new MongoValueConverter(), new JacksonJsonCodec());
                    var store = new BsonStore();
                    try (var access = custom ? new MongoDataAccess(store.client(), "test", mapper, false)
                            : new MongoDataAccess(store.client(), "test")) {
                        var metadata = EntityMetadata.inspect(DatedRow.class);
                        var row = new DatedRow(); row.id = 1;
                        for (LocalDate date : List.of(LocalDate.of(2026, 9, 19), LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1))) {
                            row.day = date;
                            access.applyBatch(List.of(WriteOperation.insert(metadata, row, row.id, null)));
                            assertEquals(BsonType.DATE_TIME, store.stored.get("day").getBsonType());
                            assertEquals(java.util.Date.class, store.read().get("day").getClass());
                            assertEquals(date, access.findById(metadata, 1L).orElseThrow().day, zone);
                            assertEquals(date, access.find(metadata, Query.where("id", 1L)).getFirst().day, zone);
                            List<DatedRow> scanned = new ArrayList<>(); access.scan(metadata, scanned::add);
                            assertEquals(date, scanned.getFirst().day, zone);
                        }
                        row.day = null;
                        access.applyBatch(List.of(WriteOperation.insert(metadata, row, row.id, null)));
                        assertNull(access.findById(metadata, 1L).orElseThrow().day);
                    }
                }
            }
        } finally { TimeZone.setDefault(previous); }
    }

    @Test void binaryAndDecimalRoundTripThroughDefaultAdapterOnAllReadPaths() {
        var store = new BsonStore();
        try (var access = new MongoDataAccess(store.client(), "test")) {
            var metadata = EntityMetadata.inspect(Row.class);
            var row = new Row(); row.id = 1;
            row.payload = new byte[] {0, 1, -1, 127};
            row.total = new BigInteger("123456789012345678901234567890");
            row.price = new BigDecimal("12345678901234567890.1234567890");
            assertTrue(access.applyBatch(List.of(WriteOperation.insert(metadata, row, row.id, null))).allSuccess());
            assertInstanceOf(Binary.class, store.read().get("payload"));
            assertEquals(BsonType.DECIMAL128, store.stored.get("total").getBsonType());
            assertRow(row, access.findById(metadata, 1L).orElseThrow());
            assertRow(row, access.find(metadata, Query.where("id", 1L)).getFirst());
            List<Row> scanned = new ArrayList<>(); access.scan(metadata, scanned::add);
            assertRow(row, scanned.getFirst());
            row.payload = new byte[0]; row.total = row.total.negate();
            access.applyBatch(List.of(WriteOperation.insert(metadata, row, row.id, null)));
            assertRow(row, access.findById(metadata, 1L).orElseThrow());
            row.payload = null; row.total = null; row.price = null;
            access.applyBatch(List.of(WriteOperation.insert(metadata, row, row.id, null)));
            assertRow(row, access.findById(metadata, 1L).orElseThrow());
        }
    }

    @Test void customJsonMapperAlsoReceivesNormalizedBinary() {
        var store = new BsonStore();
        var mapper = new EntityMapper(new MongoValueConverter(), new JacksonJsonCodec());
        try (var access = new MongoDataAccess(store.client(), "test", mapper, false)) {
            var metadata = EntityMetadata.inspect(JsonRow.class);
            var row = new JsonRow(); row.id = 2; row.payload = new byte[]{42}; row.tags = List.of("gold", "silver");
            access.applyBatch(List.of(WriteOperation.insert(metadata, row, row.id, null)));
            var restored = access.findById(metadata, 2L).orElseThrow();
            assertArrayEquals(row.payload, restored.payload); assertEquals(row.tags, restored.tags);
        }
    }

    @Test void valuesBeyondDecimal128PrecisionFailInsteadOfRounding() {
        var store = new BsonStore();
        try (var access = new MongoDataAccess(store.client(), "test")) {
            var metadata = EntityMetadata.inspect(Row.class);
            var row = new Row(); row.id = 1;
            row.total = new BigInteger("123456789012345678901234567890123456789");
            assertThrows(NumberFormatException.class, () -> access.applyBatch(List.of(WriteOperation.insert(metadata, row, 1L, null))));
        }
    }

    private static void assertRow(Row expected, Row actual) {
        assertEquals(expected.id, actual.id); assertArrayEquals(expected.payload, actual.payload);
        assertEquals(expected.total, actual.total); assertEquals(expected.price, actual.price);
    }

    /** Real driver BSON codecs around a simulated collection; no MongoDB server required. */
    private static final class BsonStore {
        final DocumentCodec codec = new DocumentCodec(MongoClientSettings.getDefaultCodecRegistry());
        BsonDocument stored;
        Document read() { return codec.decode(new BsonDocumentReader(stored), DecoderContext.builder().build()); }
        MongoClient client() {
            MongoCollection<?> collection = proxy(MongoCollection.class, (p, m, a) -> switch (m.getName()) {
                case "bulkWrite" -> {
                    var models = (List<?>) a[0];
                    for (Object model : models) {
                        Document doc = (Document) ((InsertOneModel<?>) model).getDocument();
                        BsonDocument encoded = new BsonDocument();
                        codec.encode(new BsonDocumentWriter(encoded), doc, EncoderContext.builder().build());
                        stored = encoded;
                    }
                    yield BulkWriteResult.acknowledged(models.size(), 0, 0, 0, List.of(), List.of());
                }
                case "find" -> find();
                default -> throw new UnsupportedOperationException(m.toString());
            });
            MongoDatabase database = proxy(MongoDatabase.class, (p, m, a) -> switch (m.getName()) {
                case "getCollection" -> collection;
                case "getName" -> "test";
                default -> throw new UnsupportedOperationException(m.toString());
            });
            return proxy(MongoClient.class, (p, m, a) -> switch (m.getName()) {
                case "getDatabase" -> database;
                case "close" -> null;
                default -> throw new UnsupportedOperationException(m.toString());
            });
        }
        private FindIterable<?> find() {
            return proxy(FindIterable.class, (p, m, a) -> switch (m.getName()) {
                case "first" -> read();
                case "batchSize", "limit" -> p;
                case "iterator" -> {
                    Iterator<Document> iterator = List.of(read()).iterator();
                    yield proxy(MongoCursor.class, (c, method, args) -> switch (method.getName()) {
                        case "hasNext" -> iterator.hasNext();
                        case "next" -> iterator.next();
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.toString());
                    });
                }
                default -> throw new UnsupportedOperationException(m.toString());
            });
        }
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
