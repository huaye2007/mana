package cn.managame.example;

import cn.managame.runtime.time.GameTime;
import java.time.ZoneId;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Starts the game server's Spring context, its world server included, on an in-memory database in MySQL mode, and
 * keeps it until the process is stopped. Clients speak protobuf in the game's frames; {@link ExampleClient} plays a few
 * requests.
 */
public final class ExampleMain {
    private ExampleMain() {}

    public static void main(String[] args) {
        GameTime.setZone(ZoneId.systemDefault());   // the game's one time zone, set once
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(GameConfig.class);
        context.registerShutdownHook();             // Ctrl+C closes every bean before the beans it depends on
        Environment settings = context.getEnvironment();
        System.out.println("""
                The game server listens on port %s and its world server on %s.
                Play from another terminal: java -cp managame-example/target/example.jar cn.managame.example.ExampleClient 1001 ann
                Stop with Ctrl+C: the servers close in order and every change is written."""
                .formatted(settings.getProperty("example.gateway-port"), settings.getProperty("example.world-port")));
    }
}
