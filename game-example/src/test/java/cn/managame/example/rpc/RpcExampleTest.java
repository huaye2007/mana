package cn.managame.example.rpc;

import cn.managame.example.ExampleTestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RpcExampleTest extends ExampleTestSupport {
    @Test void exampleRunsOverTcp() throws Exception {
        assertEquals("hello game-rpc", RpcEchoExample.run());
    }
}
