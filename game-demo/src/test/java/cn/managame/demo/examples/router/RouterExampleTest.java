package cn.managame.demo.examples.router;

import cn.managame.demo.examples.ExampleTestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RouterExampleTest extends ExampleTestSupport {
    @Test void oneRpcNodePerSimulatedProcessRunsDynamicRoutingExample() throws Exception {
        assertEquals("hello game-router", RouterEchoExample.run());
    }
}
