package cn.managame.demo.examples.runtime;

import cn.managame.demo.examples.ExampleTestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeNotificationExampleTest extends ExampleTestSupport {
    @Test void notificationReachesItsHandler() {
        assertEquals("ready", RuntimeNotificationExample.deliver("ready"));
    }
}
