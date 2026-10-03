package cn.managame.demo.examples.network;

import cn.managame.demo.examples.ExampleTestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpAsyncServerExampleTest extends ExampleTestSupport {
    @Test void callbackExample() throws Exception {
        assertEquals("异步 HTTP 游戏服务器", HttpAsyncServerExample.roundTrip("异步 HTTP 游戏服务器"));
    }
}
