package cn.managame.network.example;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkEchoExampleTest {
    @Test void completeExample() throws Exception {
        Path sockets = Path.of("target/socket-tmp").toAbsolutePath();
        Files.createDirectories(sockets);
        if (System.getProperty("os.name").startsWith("Windows")) {
            // JDK PipeImpl falls back to TCP when its AF_UNIX listener cannot bind.
            // This host intermittently fails AF_UNIX connect even with a valid long-form path.
            Path marker = sockets.resolve("tcp-pipe-only");
            Files.writeString(marker, "Test-only: force JDK Selector wakeup pipe to use TCP");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
        System.setProperty("io.netty.eventLoopThreads", "2");
        assertEquals("hello game-network", NetworkEchoExample.roundTrip("hello game-network"));
    }
}