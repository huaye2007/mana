package cn.managame.example;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.FrameworkErrorCodes;
import cn.managame.example.client.GameClient;
import cn.managame.example.login.User;
import cn.managame.example.login.UserRepository;
import cn.managame.example.proto.Login;
import cn.managame.example.proto.World;
import cn.managame.network.netty.NetworkServer;
import cn.managame.rpc.RpcNode;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * 端到端集成测试：在同一 JVM 里以 Spring 分别启动纯净的世界服（WorldServerApp）与游戏服（GameServerApp），
 * 验证客户端登录、MySQL(H2) 仓储落库与跨进程 RPC 进入世界。
 */
class GameServerIntegrationTest {

    @Test void fullGameWorldRpcAndDataFlow() throws Exception {
        try (var world = startWorld();
             var game = startGame(world.getBean(RpcNode.class).port())) {

            // 等待游戏服与世界服 RPC 握手完成
            RpcNode gameRpc = game.getBean(RpcNode.class);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!gameRpc.peer(NodeConstant.WORLD).isConnected() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(gameRpc.peer(NodeConstant.WORLD).isConnected(), "game server should connect to world server");

            // 真实 TCP 客户端连接游戏服 -> 登录（写 UserRepository）-> 跨 RPC 进入世界服
            int gamePort = game.getBean(NetworkServer.class).port();
            long userId = 10001L;
            long roleId;
            try (GameClient client = GameClient.connect(new InetSocketAddress("127.0.0.1", gamePort),
                    Protocols.game(), push -> {}, Duration.ofSeconds(5))) {
                GamePacket loginAnswer = client.request(Login.LoginReq.newBuilder()
                        .setServerId(1).setToken("tok").setUserId(userId).build()).get(5, TimeUnit.SECONDS);
                assertEquals(FrameworkErrorCodes.SUCCESS, loginAnswer.getCode());
                roleId = ((Login.LoginRes) loginAnswer.getBody()).getRoleId();
                assertTrue(roleId > 0);

                GamePacket worldAnswer = client.request(World.EnterWorldReq.getDefaultInstance()).get(5, TimeUnit.SECONDS);
                assertEquals(FrameworkErrorCodes.SUCCESS, worldAnswer.getCode());
                assertEquals(1, ((World.JoinWorldRes) worldAnswer.getBody()).getOnline());
            }

            User saved = game.getBean(UserRepository.class).get(userId);
            assertNotNull(saved);
            assertEquals(roleId, saved.getRoleIdMap().get(1));
        }
    }

    private static AnnotationConfigApplicationContext startWorld() {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("world-test", Map.of(
                "rpc.node", NodeConstant.WORLD,
                "rpc.port", 0)));
        ctx.register(WorldServerApp.class);
        ctx.refresh();
        return ctx;
    }

    private static AnnotationConfigApplicationContext startGame(int worldRpcPort) throws Exception {
        String jdbcUrl = "jdbc:h2:mem:example_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (var conn = java.sql.DriverManager.getConnection(jdbcUrl, "sa", "test");
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE `user` (`user_id` BIGINT NOT NULL, `role_id_map` MEDIUMTEXT NOT NULL, PRIMARY KEY (`user_id`))");
        }
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("game-test", Map.of(
                "rpc.node", 2,
                "rpc.peers", NodeConstant.WORLD + "=127.0.0.1:" + worldRpcPort,
                "game.port", 0,
                "ai.mcp.port", 0,
                "mysql.url", jdbcUrl,
                "mysql.user", "sa",
                "mysql.password", "test")));
        ctx.register(GameServerApp.class);
        ctx.refresh();
        return ctx;
    }
}
