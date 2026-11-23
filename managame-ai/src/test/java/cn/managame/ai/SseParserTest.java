package cn.managame.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.ai.internal.SseParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseParserTest {

    @Test void parsesMultiLineEventsCommentsIdsAndTrailingEof() {
        SseParser parser = new SseParser();
        List<AiChunk> chunks = new ArrayList<>();
        String[] lines = {
                ": keepalive comment",
                "id: 1",
                "event: delta",
                "data: hello",
                "data: world",
                "",
                "data: second",
                "",
                "event: done",
                "data: [END]"
        };
        for (String line : lines) parser.feedLine(line, chunks::add);
        parser.finish(chunks::add);

        assertEquals(3, chunks.size());
        assertEquals(new AiChunk("delta", "hello\nworld", "1"), chunks.get(0));
        assertEquals(new AiChunk("message", "second", "1"), chunks.get(1));
        assertEquals(new AiChunk("done", "[END]", "1"), chunks.get(2));
    }

    @Test void basicCodecHandlesStringsScalarsAndRejectsComplexTypes() throws Exception {
        AiJsonCodec codec = AiJsonCodec.basic();
        assertEquals("\"hi\\n\\\"there\\\"\"", codec.encode("hi\n\"there\""));
        assertEquals("42", codec.encode(42));
        assertEquals("true", codec.encode(true));
        assertEquals("null", codec.encode(null));

        assertEquals("raw", codec.decode("raw", String.class));
        assertEquals(42, codec.decode("42", int.class));
        assertEquals(99L, codec.decode("99", Long.class));
        assertEquals(true, codec.decode("true", boolean.class));
        assertNull(codec.decode("null", Long.class));

        record Custom(int x) {}
        assertThrows(UnsupportedOperationException.class, () -> codec.encode(new Custom(1)));
        assertThrows(UnsupportedOperationException.class, () -> codec.decode("{\"x\":1}", Custom.class));
    }
}
