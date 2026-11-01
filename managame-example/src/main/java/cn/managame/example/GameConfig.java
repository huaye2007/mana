package cn.managame.example;

import cn.managame.IdGenerator;
import cn.managame.IdLayout;
import cn.managame.SnowflakeIdGenerator;
import cn.managame.data.GameData;
import cn.managame.data.mysql.MysqlEntityMapper;
import cn.managame.data.mysql.MysqlLogWriter;
import cn.managame.example.Model.Bags;
import cn.managame.example.Model.GoldLogs;
import cn.managame.example.Model.Players;
import cn.managame.example.proto.LoginRequest;
import cn.managame.network.netty.ConnectionInitializer;
import cn.managame.network.netty.NetworkHandlers;
import cn.managame.network.netty.NetworkServer;
import cn.managame.rpc.RpcNode;
import cn.managame.rpc.RpcPeer;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.dispatch.Dispatcher;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.timer.GameTimer;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

/**
 * The game server, every component a Spring bean, joined in the game's own code: the components know nothing of
 * Spring. Spring closes each bean before the beans it depends on, so the dependencies give the shutdown order: the
 * entries (gateway, timers, RPC) close first, then the runtime, which finishes what it accepted, and the data last,
 * which writes all of it. That order is what keeps shutdown correct; no component counts work in flight.
 */
@Configuration
@ComponentScan
@PropertySource("classpath:example.properties")
class GameConfig {
    static final int PLAYER = 1, SYSTEM = 2;
    /** The game's own error codes, above the framework's. */
    static final int NOT_ENOUGH_GOLD = 10_001, UNKNOWN_ITEM = 10_002;
    static final int NODE_ID = 2;
    private static final System.Logger LOG = System.getLogger("cn.managame.example");

    /** H2 in MySQL mode with the game's tables; a real game gives a pool over MySQL's driver. */
    @Bean DataSource dataSource(@Value("${example.database-url}") String url) throws SQLException {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL(url);
        Schema.create(database);
        return database;
    }

    @Bean GameData gameData(DataSource dataSource) {
        return GameData.builder()
                .repositories(new MysqlEntityMapper(dataSource), List.of(Players.class, Bags.class))
                .logRepositories(new MysqlLogWriter(dataSource), List.of(GoldLogs.class))
                .errorHandler(failure -> LOG.log(Level.ERROR, "Not saved: " + failure.value(), failure.cause()))
                .build();
    }

    /** Depends on the data, so it closes first: its tasks finish before the data writes the last of their changes. */
    @Bean @DependsOn("gameData") GameRuntime gameRuntime() {
        return GameRuntime.builder()
                .domain(PLAYER, RouteExecutors.virtualThreads(), GameConfig::playerRoute)
                .domain(SYSTEM, RouteExecutors.striped(1), RouteKeys.constant(0))
                .build();
    }

    /** The player a message acts for: the one logging in, else the session's player; a session nobody owns is refused. */
    private static long playerRoute(Context source, Object message) {
        if (message instanceof LoginRequest login) return login.getPlayerId();
        if (source.businessId() == 0) throw new IllegalArgumentException("not logged in");
        return source.businessId();
    }

    /** The world this game server calls; here in the same process, elsewhere its address from configuration. */
    @Bean WorldServer worldServer(@Value("${example.world-port}") int port) { return new WorldServer(port); }

    /** Closes before the runtime its answers go back to, and before the world it dials. */
    @Bean @DependsOn({"gameRuntime", "worldServer"}) RpcNode rpcNode() { return RpcNode.builder().nodeId(NODE_ID).start(); }

    /** The world's peer, waited for at startup: a game server is no use without its world. */
    @Bean RpcPeer world(RpcNode rpcNode, WorldServer worldServer) throws InterruptedException {
        RpcPeer world = rpcNode.createPeer(WorldServer.NODE_ID, new InetSocketAddress("127.0.0.1", worldServer.port()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!world.isConnected() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(10);
        if (!world.isConnected()) throw new IllegalStateException("The world server did not answer");
        return world;
    }

    /** Item ids unique across servers: the server id fills the data part. */
    @Bean IdGenerator itemIds() { return new SnowflakeIdGenerator(IdLayout.DEFAULT, NODE_ID); }

    /** Every component handed over as a collection: the Dispatcher takes the {@code @HandleMethod} methods among them. */
    @Bean Dispatcher dispatcher(GameRuntime gameRuntime, ApplicationContext context) {
        return Dispatcher.builder(gameRuntime).register(context.getBeansWithAnnotation(Component.class).values()).build();
    }

    /** Every component handed over again: the timer schedules the {@code @Cron} methods among them. */
    @Bean GameTimer gameTimer(GameRuntime gameRuntime, ApplicationContext context) {
        GameTimer timer = new GameTimer(gameRuntime);
        timer.register(context.getBeansWithAnnotation(Component.class).values());
        return timer;
    }

    /**
     * Started last, once all it hands work to runs; closed first, as it depends on all of it. Network's length-field
     * framing, then the game's own packet codec: whole protobuf messages reach the gateway.
     */
    @Bean NetworkServer gateway(Dispatcher dispatcher, OnlinePlayers online, @Value("${example.gateway-port}") int port) {
        return NetworkServer.start(new InetSocketAddress(port), ConnectionInitializer.tcp()
                .addLast(NetworkHandlers.lengthFieldFraming(64 * 1024))
                .addLast("packet-codec", PacketCodec.INSTANCE)
                .handler(new Gateway(dispatcher, online))
                .build());
    }
}
