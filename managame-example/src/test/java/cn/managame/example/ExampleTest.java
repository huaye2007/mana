package cn.managame.example;

import static cn.managame.FrameworkErrorCodes.INVALID_ROUTE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.managame.example.proto.BagRequest;
import cn.managame.example.proto.BagResponse;
import cn.managame.example.proto.BuyRequest;
import cn.managame.example.proto.BuyResponse;
import cn.managame.example.proto.ItemStack;
import cn.managame.example.proto.LoginRequest;
import cn.managame.example.proto.LoginResponse;
import cn.managame.example.proto.RankRequest;
import cn.managame.example.proto.RankResponse;
import cn.managame.network.netty.NetworkServer;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Plays the example with its client on managame-network, protobuf over real connections, then reads what was saved. */
class ExampleTest {
    private static final String DATABASE = "jdbc:h2:mem:exampleTest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    /** The game server's context, on ports the system picks and a database of the test's own. */
    private static AnnotationConfigApplicationContext startGame() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("example.gateway-port", "0", "example.world-port", "0", "example.database-url", DATABASE)));
        context.register(GameConfig.class);
        context.refresh();
        return context;
    }

    private static LoginRequest login(long playerId, String name) {
        return LoginRequest.newBuilder().setPlayerId(playerId).setName(name).build();
    }

    private static BuyRequest buy(int configId) { return BuyRequest.newBuilder().setConfigId(configId).build(); }

    private static RankResponse rank(long rank, long ranked) { return RankResponse.newBuilder().setRank(rank).setRanked(ranked).build(); }

    @Test void playersLogInBuyAndAreRankedByTheWorldThenEverythingIsSaved() throws Exception {
        try (AnnotationConfigApplicationContext game = startGame()) {   // closing it closes every bean in order, the data last
            InetSocketAddress gateway = new InetSocketAddress("127.0.0.1", game.getBean(NetworkServer.class).port());
            try (GameClient ann = new GameClient(gateway); GameClient bob = new GameClient(gateway)) {
                assertEquals(Packet.error(1, INVALID_ROUTE_KEY), ann.call(buy(5001)), "nobody to act for before login");
                assertEquals(new Packet(2, 0, LoginResponse.newBuilder().setName("ann").setGold(100).build()), ann.call(login(1001, "ann")));
                CompletableFuture<Packet> first = ann.request(buy(5001)), second = ann.request(buy(5001));   // sent together, matched by seq
                assertEquals(new Packet(3, 0, BuyResponse.newBuilder().setConfigId(5001).setGold(70).build()), first.get(5, TimeUnit.SECONDS));
                assertEquals(new Packet(4, 0, BuyResponse.newBuilder().setConfigId(5001).setGold(40).build()), second.get(5, TimeUnit.SECONDS));
                assertEquals(Packet.error(5, GameConfig.NOT_ENOUGH_GOLD), ann.call(buy(5003)));
                assertEquals(Packet.error(6, GameConfig.UNKNOWN_ITEM), ann.call(buy(9999)));
                BagResponse bag = BagResponse.newBuilder().addItems(ItemStack.newBuilder().setConfigId(5001).setAmount(2)).build();
                assertEquals(new Packet(7, 0, bag), ann.call(BagRequest.getDefaultInstance()));
                assertEquals(new Packet(8, 0, rank(1, 1)), ann.call(RankRequest.getDefaultInstance()));
                assertEquals(new Packet(1, 0, LoginResponse.newBuilder().setName("bob").setGold(100).build()), bob.call(login(1002, "bob")));
                assertEquals(new Packet(2, 0, rank(1, 2)), bob.call(RankRequest.getDefaultInstance()), "bob has more gold");
                assertEquals(new Packet(9, 0, rank(2, 2)), ann.call(RankRequest.getDefaultInstance()));
            }
            try (Socket broken = new Socket("127.0.0.1", gateway.getPort())) {   // not our client: one breaking the protocol
                broken.setSoTimeout(5000);
                DataOutputStream out = new DataOutputStream(broken.getOutputStream());
                out.writeInt(12);
                out.write(ByteBuffer.allocate(12).putInt(99).putInt(1).putInt(0).array());   // message id 99: none
                assertEquals(-1, broken.getInputStream().read(), "it is closed");
            }
        }
        assertEquals(List.of(List.of(1001L, "ann", 40L, 1), List.of(1002L, "bob", 100L, 1)),
                rows("SELECT id, name, gold, best_rank FROM player ORDER BY id"));
        assertEquals(List.of(List.of(1001L, 5001, 2)), rows("SELECT player_id, config_id, amount FROM item"));
        assertEquals(List.of(List.of(1001L, -30L, "buy 5001"), List.of(1001L, -30L, "buy 5001")),
                rows("SELECT player_id, delta, reason FROM gold_log"));
        assertEquals(List.of(List.of(2L)), rows("SELECT COUNT(*) FROM player WHERE last_logout > 0"), "both logged out");
    }

    private static List<List<Object>> rows(String sql) throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL(DATABASE);
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
