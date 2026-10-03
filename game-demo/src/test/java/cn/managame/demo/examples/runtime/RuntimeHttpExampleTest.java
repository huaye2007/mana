package cn.managame.demo.examples.runtime;

import cn.managame.demo.examples.ExampleTestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeHttpExampleTest extends ExampleTestSupport {
    @Test void annotatedEchoAndDeferredRouteCallback() throws Exception {
        assertEquals("Runtime HTTP 游戏服务器", RuntimeHttpExample.roundTrip("Runtime HTTP 游戏服务器"));
    }
}
