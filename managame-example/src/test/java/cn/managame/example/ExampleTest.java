package cn.managame.example;

import static cn.managame.FrameworkErrorCodes.INVALID_ROUTE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/** Plays the example as a client would, over real connections, then reads what the game server saved. */
class ExampleTest {
    /** A player's telnet session: one line out, one line back. */
    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final PrintWriter out;
        private final BufferedReader in;

        Client(int port) throws Exception {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        String send(String line) throws Exception {
            out.println(line);
            return in.readLine();
        }

        @Override public void close() throws Exception { socket.close(); }
    }

    @Test void playersLogInBuyAndAreRankedByTheWorldThenEverythingIsSaved() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:exampleTest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        Schema.create(database);
        try (WorldServer world = new WorldServer(0)) {
            GameServer game = new GameServer(database, new InetSocketAddress("127.0.0.1", world.port()), 0);
            try (Client ann = new Client(game.gatewayPort()); Client bob = new Client(game.gatewayPort())) {
                assertEquals("1 err " + INVALID_ROUTE_KEY, ann.send("1 buy 5001"), "nobody to act for before login");
                assertEquals("2 ok welcome ann, gold 100", ann.send("2 login 1001 ann"));
                assertEquals("3 ok bought 5001, gold 70", ann.send("3 buy 5001"));
                assertEquals("4 ok bought 5001, gold 40", ann.send("4 buy 5001"));
                assertEquals("5 err " + GameServer.NOT_ENOUGH_GOLD, ann.send("5 buy 5003"));
                assertEquals("6 err bad request", ann.send("6 dance"));
                assertEquals("7 ok 5001x2", ann.send("7 bag"));
                assertEquals("8 ok rank 1 of 1", ann.send("8 rank"));
                assertEquals("1 ok welcome bob, gold 100", bob.send("1 login 1002 bob"));
                assertEquals("2 ok rank 1 of 2", bob.send("2 rank"), "bob has more gold");
                assertEquals("9 ok rank 2 of 2", ann.send("9 rank"));
            } finally {
                game.close();   // the gateway first, the data last: every change and log is written
            }
        }
        assertEquals(List.of(List.of(1001L, "ann", 40L, 1), List.of(1002L, "bob", 100L, 1)),
                rows(database, "SELECT id, name, gold, best_rank FROM player ORDER BY id"));
        assertEquals(List.of(List.of(1001L, 5001, 2)), rows(database, "SELECT player_id, config_id, amount FROM item"));
        assertEquals(List.of(List.of(1001L, -30L, "buy 5001"), List.of(1001L, -30L, "buy 5001")),
                rows(database, "SELECT player_id, delta, reason FROM gold_log"));
        assertEquals(List.of(List.of(2L)), rows(database, "SELECT COUNT(*) FROM player WHERE last_logout > 0"), "both logged out");
    }

    private static List<List<Object>> rows(JdbcDataSource database, String sql) throws Exception {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            List<List<Object>> rows = new ArrayList<>();
            while (result.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) row.add(result.getObject(i));
                rows.add(row);
            }
            return rows;
        }
    }
}
