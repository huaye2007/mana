package cn.managame.ai;

import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtobufJsonTest {
    public record Note(long id, String text) {}

    @Test void protoJsonKeepsInt64AsStringAndUsesConfiguredFallbackCodec() {
        var codec = new ProtobufJson(List.of(Int64Value.getDefaultInstance()), AiJson.jacksonStringLongs());
        var message = Int64Value.of(9007199254740993L);
        assertEquals("\"9007199254740993\"", codec.encode(message));
        assertEquals(message, codec.decode(codec.encode(message), Int64Value.class));
        assertEquals("boots", codec.decode("\"boots\"", String.class));
        assertEquals("{\"id\":\"9007199254740993\",\"text\":\"ok\"}", codec.encode(new Note(9007199254740993L, "ok")));
        assertEquals(new Note(9007199254740993L, "ok"), codec.decode(Map.of("id", "9007199254740993", "text", "ok"), Note.class));
        assertNull(codec.outputSchema(Int64Value.class));
        assertNotNull(codec.outputSchema(Note.class));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{}", StringValue.class));
    }
}
