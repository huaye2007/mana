package cn.managame.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.ai.internal.BasicAiCodec;
import cn.managame.ai.internal.Json;
import cn.managame.ai.internal.SseParser;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JsonAndSseTest {
    enum Rarity { COMMON, EPIC }
    record Item(int id, String name, Rarity rarity) {}
    record Bag(long roleId, List<Item> items, Optional<String> note, boolean locked) {}

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

    @Test void basicCodecDecodesRecordsAndCollections() throws Exception {
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
    }

    @Test void sseParserHandlesSplitUtf8AndMultilineEvents() {
        List<AiStreamEvent> events = new ArrayList<>();
        SseParser parser = new SseParser(events::add);
        String stream = "\uFEFF: keep-alive\r\n"
                + "id: 1\r\n"
                + "event: delta\r\n"
                + "data: 你好\r\n"
                + "data: 世界\r\n"
                + "\r\n"
                + "data: plain\n\n"
                + "event: done\ndata: [END]";
        byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
        // Feed 1 byte at a time to stress split multi-byte UTF-8 and CRLF boundaries
        for (byte b : bytes) {
            parser.feed(ByteBuffer.wrap(new byte[]{b}));
        }
        parser.finish();

        assertEquals(3, events.size());
        assertEquals(new AiStreamEvent("delta", "你好\n世界", "1"), events.get(0));
        assertEquals(new AiStreamEvent("message", "plain", "1"), events.get(1));
        assertEquals(new AiStreamEvent("done", "[END]", "1"), events.get(2));
        assertTrue(events.stream().noneMatch(e -> e.data().contains("keep-alive")));
    }
}
