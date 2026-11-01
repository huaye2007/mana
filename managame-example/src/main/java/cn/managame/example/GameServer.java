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
import cn.managame.network.connection.Connection;
import cn.managame.network.netty.ConnectionInitializer;
import cn.managame.network.netty.NetworkServer;
import cn.managame.rpc.RpcListener;
import cn.managame.rpc.RpcNode;
import cn.managame.rpc.RpcPeer;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.dispatch.Dispatcher;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.timer.GameTimer;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * A game server, every component joined in the application's own code: no framework project does it. It starts from
 * the bottom up, the gateway last once all it hands work to runs, and stops from the top down, the data last once
 * nothing changes it any more. That order is what keeps the shutdown correct; no component counts work in flight.
 */
final class GameServer implements AutoCloseable {
    static final int PLAYER = 1, SYSTEM = 2;
    /** The game's own error codes, above the framework's. */
    static final int NOT_ENOUGH_GOLD = 10_001, UNKNOWN_ITEM = 10_002;
    static final int NODE_ID = 2;
    private static final System.Logger LOG = System.getLogger("cn.managame.example");
    private final GameData data;
    private final GameRuntime runtime;
    private final RpcNode rpc;
    private final GameTimer timer;
    private final NetworkServer gateway;

    GameServer(DataSource dataSource, InetSocketAddress worldAddress, int gatewayPort) throws InterruptedException {
        data = GameData.builder()
                .repositories(new MysqlEntityMapper(dataSource), List.of(Players.class, Bags.class))
                .logRepositories(new MysqlLogWriter(dataSource), List.of(GoldLogs.class))
                .errorHandler(failure -> LOG.log(Level.ERROR, "Not saved: " + failure.value(), failure.cause()))
                .build();
        runtime = GameRuntime.builder()
                .domain(PLAYER, RouteExecutors.virtualThreads(), GameServer::playerRoute)
                .domain(SYSTEM, RouteExecutors.striped(1), RouteKeys.constant(0))
                .build();

        CountDownLatch worldUp = new CountDownLatch(1);
        rpc = RpcNode.builder().nodeId(NODE_ID).listener(new RpcListener() {
            @Override public void onConnected(RpcPeer peer, boolean restarted) { worldUp.countDown(); }
        }).start();
        RpcPeer world = rpc.createPeer(WorldServer.NODE_ID, worldAddress);
        if (!worldUp.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("The world server at " + worldAddress + " did not answer");

        Map<Long, Connection> online = new ConcurrentHashMap<>();
        IdGenerator itemIds = new SnowflakeIdGenerator(IdLayout.DEFAULT, NODE_ID);   // the server id tells its ids apart
        Dispatcher dispatcher = Dispatcher.builder(runtime)
                .register(List.of(new PlayerHandlers(runtime, data, world, itemIds, online)))
                .build();
        timer = new GameTimer(runtime);
        timer.register(List.of(new Jobs(data, online)));

        gateway = NetworkServer.start(new InetSocketAddress(gatewayPort), ConnectionInitializer.tcp()
                .addLast("lines", () -> new LineBasedFrameDecoder(1024))
                .addLast("text-in", new StringDecoder(StandardCharsets.UTF_8))
                .addLast("text-out", new StringEncoder(StandardCharsets.UTF_8))
                .handler(new Gateway(dispatcher, online))
                .build());
    }

    /** The player a message acts for: the one logging in, else the session's player; a session nobody owns is refused. */
    private static long playerRoute(Context source, Object message) {
        if (message instanceof Messages.Login login) return login.playerId();
        if (source.businessId() == 0) throw new IllegalArgumentException("not logged in");
        return source.businessId();
    }

    int gatewayPort() { return gateway.port(); }

    /**
     * The entries first, so no new work comes in: the gateway (its connections close, and their players log out on the
     * runtime), the timers and RPC. Then the runtime, which finishes what it accepted, and the data last, which writes
     * everything that work changed.
     */
    @Override public void close() {
        gateway.close();
        timer.close();
        rpc.close();
        runtime.close();
        data.close();
    }
}
