package cn.managame.example.network;

import cn.managame.example.ExampleTestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkEchoExampleTest extends ExampleTestSupport {
    @Test void completeExample() throws Exception {
        assertEquals("hello game-network", NetworkEchoExample.roundTrip("hello game-network"));
    }
}
