package cn.managame.example;

import cn.managame.runtime.time.GameTime;
import java.net.InetSocketAddress;
import java.time.ZoneId;
import org.h2.jdbcx.JdbcDataSource;

/**
 * Runs a world server and a game server in one process, on an in-memory database in MySQL mode, until the process is
 * stopped. Play over telnet: {@code telnet 127.0.0.1 9001}, then {@code 1 login 1001 ann}, {@code 2 buy 5001},
 * {@code 3 bag}, {@code 4 rank}.
 */
public final class ExampleMain {
    private ExampleMain() {}

    public static void main(String[] args) throws Exception {
        GameTime.setZone(ZoneId.systemDefault());   // the game's one time zone, set once
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:example;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        Schema.create(database);

        WorldServer world = new WorldServer(7001);
        GameServer game = new GameServer(database, new InetSocketAddress("127.0.0.1", 7001), 9001);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            game.close();    // the game server stops before the world it calls
            world.close();
        }));
        System.out.println("""
                The game server listens on port 9001 and its world server on 7001.
                Play with: telnet 127.0.0.1 9001
                  1 login 1001 ann
                  2 buy 5001
                  3 bag
                  4 rank
                Stop with Ctrl+C: the servers close in order and every change is written.""");
    }
}
