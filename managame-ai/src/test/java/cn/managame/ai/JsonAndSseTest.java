package cn.managame.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.ai.annotation.McpParam;
import cn.managame.ai.internal.BasicAiCodec;
import cn.managame.ai.internal.Json;
import cn.managame.ai.internal.JsonSchema;
import cn.managame.ai.internal.SseParser;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JsonAndSseTest {
    enum Rarity { COMMON, EPIC }
    record Item(int id, String name, Rarity rarity) {}
    record Bag(long roleId, List<Item> items, Optional<String> note, boolean locked) {}
    record AuditEntry(UUID id, Instant at, LocalDate day, Duration cost, URI endpoint,
            @McpParam(value = "Required title", required = true) String title) {}

    @Test void jsonRoundTripAndEscapes() {
        Map<String, Object> input = Map.of(
                "text", "line1\nline2\t\"quoted\"\\slash\u0007中文",
                "int", 42L,
                "neg", -1234567890123L,
                "flt", 3.5,
                "flag", true,
                "list", List.of(1L, "two", false)
        );
        String encoded = Json.write(input);
        Map<String, Object> decoded = Json.parseObject(encoded);
        assertEquals(input.get("text"), decoded.get("text"));
        assertEquals(42L, decoded.get("int"));
        assertEquals(-1234567890123L, decoded.get("neg"));
        assertEquals(3.5, decoded.get("flt"));
        assertEquals(true, decoded.get("flag"));
        assertEquals(List.of(1L, "two", false), decoded.get("list"));
    }

    @Test void jsonRejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{trailing} extra"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"unterminated"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"raw\nnewline\""));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1, 2,]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": 01}"));
        String deep = "[".repeat(70) + "]".repeat(70);
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep));
    }

    @Test void basicCodecDecodesRecordsUuidJavaTimeAndValidatesRequiredFields() throws Exception {
        AiCodec codec = AiCodec.basic();
        Bag bag = new Bag(1001L, List.of(new Item(10, "Sword", Rarity.EPIC)), Optional.of("vip"), true);
        String json = codec.encode(bag);
        Bag decoded = (Bag) codec.decode(json, Bag.class);
        assertEquals(bag, decoded);

        Bag noNote = (Bag) codec.decode("{\"roleId\":7,\"items\":[],\"locked\":false}", Bag.class);
        assertEquals(Optional.empty(), noNote.note());
        assertEquals(7L, noNote.roleId());

        int[] nums = (int[]) codec.decode("[1,2,3]", int[].class);
        assertArrayEquals(new int[]{1, 2, 3}, nums);
        assertNull(codec.decode("null", String.class));
        assertThrows(IllegalArgumentException.class, () -> BasicAiCodec.INSTANCE.decode("null", int.class));

        // UUID, Instant, LocalDate, Duration, URI round-trip and string JSON Schema (never JDK internal fields)
        AuditEntry entry = new AuditEntry(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                Instant.parse("2026-11-25T04:00:00Z"),
                LocalDate.of(2026, 11, 25),
                Duration.ofMillis(250),
                URI.create("http://127.0.0.1:8081/api"),
                "daily");
        String entryJson = codec.encode(entry);
        assertEquals(entry, codec.decode(entryJson, AuditEntry.class));

        Map<String, Object> schema = Json.parseObject(JsonSchema.forObjectType(AuditEntry.class));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> props = (Map<String, Map<String, Object>>) schema.get("properties");
        assertEquals("string", props.get("id").get("type"));
        assertEquals("string", props.get("at").get("type"));
        assertEquals("string", props.get("day").get("type"));
        assertEquals("string", props.get("cost").get("type"));
        assertEquals("string", props.get("endpoint").get("type"));

        // Explicit null for @McpParam(required = true) must fail validation
        String withNullTitle = entryJson.replace("\"daily\"", "null");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(withNullTitle, AuditEntry.class));
    }

    @Test void basicCodecParsesNumericStringsIncludingFull64BitSnowflakeIds() throws Exception {
        AiCodec codec = AiCodec.basic();
        long snowflake = 1795519194523264001L;
        Bag fromQuotedId = (Bag) codec.decode(
                "{\"roleId\":\"1795519194523264001\",\"items\":[{\"id\":\"10\",\"name\":\"Sword\",\"rarity\":\"EPIC\"}],\"locked\":false}",
                Bag.class);
        assertEquals(snowflake, fromQuotedId.roleId());
        assertEquals(10, fromQuotedId.items().getFirst().id());

        assertEquals(3.25d, (Double) codec.decode("\"3.25\"", double.class));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("\"12.5\"", long.class));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("\"999999999999999999999999\"", long.class));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("\"\"", long.class));
    }

    @Test void sseParserHandlesSplitUtf8MultilineEventsAndDiscardsUnterminatedTail() {
        List<AiStreamEvent> events = new ArrayList<>();
        SseParser parser = new SseParser(events::add);
        String stream = "\uFEFF: keep-alive\r\n"
                + "id: 1\r\n"
                + "event: delta\r\n"
                + "data: 你好\r\n"
                + "data: 世界\r\n"
                + "\r\n"
                + "data: plain\n\n"
                + "event: done\ndata: [END]\n\n"
                + "event: truncated\ndata: {\"half\":";
        byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
        // Feed 1 byte at a time to stress 4-byte UTF-8 remainder and CRLF boundaries
        for (byte b : bytes) {
            parser.feed(ByteBuffer.wrap(new byte[]{b}));
        }
        parser.finish();

        // Per W3C SSE spec, the unterminated trailing fragment without blank line is discarded
        assertEquals(3, events.size());
        assertEquals(new AiStreamEvent("delta", "你好\n世界", "1"), events.get(0));
        assertEquals(new AiStreamEvent("message", "plain", "1"), events.get(1));
        assertEquals(new AiStreamEvent("done", "[END]", "1"), events.get(2));
        assertTrue(events.stream().noneMatch(e -> e.data().contains("keep-alive")));
    }

    @Test void sseParserRejectsOversizedLineAndEvent() {
        SseParser lineBounded = new SseParser(e -> {}, 16);
        IllegalArgumentException lineEx = assertThrows(IllegalArgumentException.class,
                () -> lineBounded.feed(ByteBuffer.wrap((": " + "x".repeat(100) + "\n").getBytes(StandardCharsets.UTF_8))));
        assertTrue(lineEx.getMessage().contains("SSE line exceeded"));

        List<AiStreamEvent> exact = new ArrayList<>();
        SseParser exactBounded = new SseParser(exact::add, 10);
        exactBounded.feed(ByteBuffer.wrap("data: 1234567890\n\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(new AiStreamEvent("message", "1234567890", "")), exact);

        SseParser eventBounded = new SseParser(e -> {}, 10);
        eventBounded.feed(ByteBuffer.wrap("data: 123456\n".getBytes(StandardCharsets.UTF_8)));
        IllegalArgumentException eventEx = assertThrows(IllegalArgumentException.class,
                () -> eventBounded.feed(ByteBuffer.wrap("data: 78901\n\n".getBytes(StandardCharsets.UTF_8))));
        assertTrue(eventEx.getMessage().contains("SSE event exceeded"));
    }
}
