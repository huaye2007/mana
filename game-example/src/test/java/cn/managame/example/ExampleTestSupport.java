package cn.managame.example;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;

/** Test-only networking settings, independent of component-internal test helpers. */
public abstract class ExampleTestSupport {
    @BeforeAll protected static void environment() throws Exception {
        System.setProperty("io.netty.eventLoopThreads", "2");
        System.setProperty("io.netty.leakDetection.level", "paranoid");
        if (System.getProperty("os.name").startsWith("Windows")) {
            // Force the JDK Selector wakeup pipe to use TCP on this Windows host.
            Path marker = Path.of("target/tcp-pipe-only").toAbsolutePath();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "test-only selector pipe TCP fallback");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
    }
}
