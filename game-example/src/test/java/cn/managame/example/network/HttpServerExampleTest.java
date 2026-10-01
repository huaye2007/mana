package cn.managame.example.network;

import cn.managame.example.ExampleTestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpServerExampleTest extends ExampleTestSupport {
    @Test void completeExample() throws Exception {
        assertEquals("hello HTTP/1.1 游戏服务器", HttpServerExample.roundTrip("hello HTTP/1.1 游戏服务器"));
    }
}
