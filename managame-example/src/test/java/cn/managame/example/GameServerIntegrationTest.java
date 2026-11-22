package cn.managame.example;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.FrameworkErrorCodes;
import cn.managame.ai.AiEvent;
import cn.managame.ai.AiEventSink;
import cn.managame.ai.AiRequest;
import cn.managame.ai.AiTransport;
import cn.managame.example.ai.AiToolGateway;
import cn.managame.example.ai.GameplayAi;
import cn.managame.example.ai.PlayerAiTools;
import cn.managame.example.client.GameClient;
import cn.managame.example.proto.Login;
import cn.managame.example.proto.World;
import cn.managame.network.netty.NetworkServer;
import cn.managame.rpc.RpcNode;
import cn.managame.runtime.GameContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;

/**
 * 端到端集成测试：在同一 JVM 里以 Spring 分别启动纯净的世界服（WorldServerApp）与游戏服（GameServerApp），
 * 验证客户端登录、MySQL(H2) 仓储落库、跨进程 RPC 进入世界、AI 事件上报以及按受众隔离的游戏服工具调用。
 */
class GameServerIntegrationTest {
    private static final CompletableFuture<AiEvent> LOGIN_EVENT = new CompletableFuture<>();
    private static final AtomicReference<Object> LAST_PROPOSAL = new AtomicReference<>();

    @Configuration
    static class TestAiBindings {
        @Bean @Primary
        AiEventSink testEventSink() {
            return batch -> {
                for (AiEvent event : batch) {
                    if ("user.logged_in".equals(event.event())) LOGIN_EVENT.complete(event);
                }
            };
        }

        @Bean @Primary
        AiTransport testTransport() {
            return (AiRequest request, Duration remaining) -> "{\"selected\":\"boots\"}";
        }

        @Bean @Primary
        AiToolGateway.ProposalSink testProposalSink() {
            return (caller, tool, message) -> {
                LAST_PROPOSAL.set(message);
                return CompletableFuture.completedFuture(Map.of("status", "PENDING_APPROVAL", "tool", tool.name()));
            };
        }
    }

    @Test void fullGameWorldRpcDataAndAiThreePlanesFlow() throws Exception {
        try (var world = startWorld();
             var game = startGame(world.getBean(RpcNode.class).port())) {

            // 等待游戏服与世界服 RPC 握手完成
            RpcNode gameRpc = game.getBean(RpcNode.class);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!gameRpc.peer(NodeConstant.WORLD).isConnected() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(gameRpc.peer(NodeConstant.WORLD).isConnected(), "game server should connect to world server");

            // 1. 真实 TCP 客户端连接游戏服 -> 登录（写 UserRepository 并触发 AI 登录事件）-> 跨 RPC 进入世界服
            int gamePort = game.getBean(NetworkServer.class).port();
            long userId = 9007199254740993L; // 超过 JS 53 位安全整数，验证 64 位 ID 在工具边界不失真
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

            // 2. 验证出站事件流（场景 2）：登录自动通过 AiEventPublisher 按 userId 分区上报
            AiEvent loginEvent = LOGIN_EVENT.get(4, TimeUnit.SECONDS);
            assertEquals("user.logged_in", loginEvent.event());
            assertEquals(Long.toString(userId), loginEvent.partitionKey());
            assertTrue(loginEvent.payload().contains(Long.toString(roleId)));

            // 3. 验证入站工具目录（场景 1）：player 与 ops 受众隔离 + 真实读取 UserRepository + 审批路由
            AiToolGateway tools = game.getBean(AiToolGateway.class);
            assertEquals(1, tools.catalog(PlayerAiTools.AUDIENCE_PLAYER).tools().size());
            assertEquals(2, tools.catalog(PlayerAiTools.AUDIENCE_OPS).tools().size());

            // 玩家受众可见 queryUserRoles，且以 AiToolResult + 64 位字符串精确返回角色表
            assertEquals(1, tools.exportDescriptors(PlayerAiTools.AUDIENCE_PLAYER).size());
            String rolesJson = tools.invokeMap(
                    PlayerAiTools.AUDIENCE_PLAYER,
                    "queryUserRoles",
                    Map.of("userId", Long.toString(userId)),
                    GameContext.of(DomainConstant.LOGIN, userId)).toCompletableFuture().get(4, TimeUnit.SECONDS);
            JsonNode rolesNode = new ObjectMapper().readTree(rolesJson);
            assertTrue(rolesNode.path("ok").asBoolean());
            assertEquals(Long.toString(userId), rolesNode.at("/data/userId").asText());
            assertEquals(1, rolesNode.at("/data/roles/0/serverId").asInt());
            assertEquals(Long.toString(roleId), rolesNode.at("/data/roles/0/roleId").asText());

            // 高危 grantReward 对 player 受众不可见
            UUID opId = UUID.randomUUID();
            assertThrows(IllegalArgumentException.class, () -> tools.invokeMap(
                    PlayerAiTools.AUDIENCE_PLAYER,
                    "grantReward",
                    Map.of("operationId", opId.toString(), "roleId", Long.toString(roleId), "coins", 100),
                    GameContext.of(DomainConstant.PLAYER, roleId)));

            // 对 ops 受众可见，且强制路由到 ProposalSink（不直接发奖）
            String proposalJson = tools.invokeMap(
                    PlayerAiTools.AUDIENCE_OPS,
                    "grantReward",
                    Map.of("operationId", opId.toString(), "roleId", Long.toString(roleId), "coins", 100),
                    GameContext.of(DomainConstant.PLAYER, roleId)).toCompletableFuture().get(4, TimeUnit.SECONDS);
            assertTrue(proposalJson.contains("PENDING_APPROVAL"));
            var proposal = assertInstanceOf(PlayerAiTools.GrantReward.class, LAST_PROPOSAL.get());
            assertEquals(opId, proposal.operationId());
            assertEquals(roleId, proposal.roleId());
            assertEquals(100, proposal.coins());
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
                "mysql.url", jdbcUrl,
                "mysql.user", "sa",
                "mysql.password", "test")));
        ctx.register(GameServerApp.class, TestAiBindings.class);
        ctx.refresh();
        return ctx;
    }
}
