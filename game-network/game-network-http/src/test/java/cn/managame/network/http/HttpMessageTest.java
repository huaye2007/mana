package cn.managame.network.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpMessageTest {

    @Test
    void requestNormalizesHeadersAndProtectsBody() {
        byte[] body = {1, 2};
        HttpRequest request = new HttpRequest("post", "/login",
                Map.of("X-Token", List.of("a")), body, "127.0.0.1");

        body[0] = 9;
        byte[] returned = request.body();
        returned[1] = 9;

        assertEquals("POST", request.method());
        assertEquals("a", request.header("x-token").orElseThrow());
        assertArrayEquals(new byte[]{1, 2}, request.body());
    }

    @Test
    void responseOffersSimpleTextFactory() {
        HttpResponse response = HttpResponse.text(201, "ok", StandardCharsets.UTF_8);

        assertEquals(201, response.status());
        assertEquals("text/plain; charset=UTF-8", response.headers().get("content-type").getFirst());
        assertArrayEquals(new byte[]{'o', 'k'}, response.body());
    }

    @Test
    void rejectsInvalidStatus() {
        assertThrows(IllegalArgumentException.class, () -> HttpResponse.empty(700));
    }
}
