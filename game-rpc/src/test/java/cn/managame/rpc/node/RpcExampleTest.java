package cn.managame.rpc.node;

import cn.managame.rpc.example.RpcEchoExample;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RpcExampleTest extends RpcTestSupport {
    @Test void exampleRunsOverTcp() throws Exception {
        assertEquals("hello game-rpc", RpcEchoExample.run());
    }
}

