package com.github.huaye2007.mana.dev.server;

import com.github.huaye2007.mana.dev.bootstrap.FuryMessageRegistrar;
import com.github.huaye2007.mana.dev.client.ClientResponse;
import com.github.huaye2007.mana.dev.client.GameClient;
import com.github.huaye2007.mana.dev.message.HeartbeatReq;
import com.github.huaye2007.mana.dev.message.HeartbeatRes;
import com.github.huaye2007.mana.dev.message.LoginReq;
import com.github.huaye2007.mana.dev.message.LoginRes;
import com.github.huaye2007.mana.dev.protocol.GameErrorCode;
import com.github.huaye2007.mana.dev.protocol.KickConstant;
import com.github.huaye2007.mana.network.connection.ServerConnectionIdGenerator;
import com.github.huaye2007.mana.network.server.NettyTcpServer;
import com.github.huaye2007.mana.network.server.NetworkTcpServerConfig;
import com.github.huaye2007.mana.runtime.annotation.GameController;
import com.github.huaye2007.mana.runtime.annotation.GameMethod;
import com.github.huaye2007.mana.runtime.command.CommandRegistry;
import com.github.huaye2007.mana.runtime.exception.GameTaskExceptionHandlers;
import com.github.huaye2007.mana.runtime.executor.DefaultExecutorGroup;
import com.github.huaye2007.mana.runtime.executor.ExecutorGroupRegistry;
import com.github.huaye2007.mana.runtime.executor.ExecutorGroups;
import com.github.huaye2007.mana.runtime.monitor.GameTaskMonitors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 走真实全链路（GamePacket 编解码 → GameHandler → GameRouterManager → 执行器组 → 回包）
 * 验证框架行为：登陆 gate、未知命令/坏包错误帧、业务异常与崩溃的错误回包、顶号踢下线。
 * 不连 MySQL：登陆绑定用测试 controller（CMD_BIND）完成，不走 LoginController。
 */
class ServerFrameworkIntegrationTest {

    private static final int CMD_BIND = 9100;
    private static final int CMD_ECHO = 9101;
    private static final int CMD_REJECT = 9102;
    private static final int CMD_CRASH = 9103;
    private static final int CMD_UNKNOWN = 8888;

    private static PlayerSessionManager sessionManager;
    private static GameRouterManager router;
    private static NettyTcpServer server;
    private static int port;

    /** 登陆组测试 controller：绑定 roleId=userId 并回 LoginRes，模拟登陆但不落库。 */
    @GameController(group = ExecutorGroups.LOGIN)
    public static class BindController {

        private final PlayerSessionManager sessions;
        private final GameRouterManager router;

        public BindController(PlayerSessionManager sessions, GameRouterManager router) {
            this.sessions = sessions;
            this.router = router;
        }

        @GameMethod(value = CMD_BIND, routerKeyMethod = "getUserId")
        public void bind(PlayerSession session, LoginReq req) {
            session.setRoleId(req.getUserId());
            sessions.bind(session);
            LoginRes res = new LoginRes();
            res.setRoleId(req.getUserId());
            router.reply(res);
        }
    }

    /** 玩家组测试 controller：回显 / 业务拒绝 / 运行期崩溃三种出口。 */
    @GameController(group = ExecutorGroups.PLAYER)
    public static class PlayerOpsController {

        private final GameRouterManager router;

        public PlayerOpsController(GameRouterManager router) {
            this.router = router;
        }

        @GameMethod(value = CMD_ECHO)
        public void echo(Long roleId, HeartbeatReq req) {
            HeartbeatRes res = new HeartbeatRes();
            res.setTime(req.getTime());
            router.reply(res);
        }

        @GameMethod(value = CMD_REJECT)
        public void reject(Long roleId, HeartbeatReq req) {
            throw new GameBusinessException(GameErrorCode.BAD_REQUEST, "reject for test");
        }

        @GameMethod(value = CMD_CRASH)
        public void crash(Long roleId, HeartbeatReq req) {
            throw new IllegalStateException("boom");
        }
    }

    @BeforeAll
    static void startServer() {
        // 进程级单例，跨测试共享：组/命令都幂等注册
        registerGroupIfAbsent(ExecutorGroups.LOGIN, "it-login");
        registerGroupIfAbsent(ExecutorGroups.PLAYER, "it-player");
        FuryMessageRegistrar.registerMessageTypes();
        GameTaskFailureReplier failureReplier = new GameTaskFailureReplier();
        GameTaskExceptionHandlers.setHandler(failureReplier);
        GameTaskMonitors.setMonitor(failureReplier);

        sessionManager = new PlayerSessionManager();
        router = new GameRouterManager(sessionManager);
        if (CommandRegistry.getInstance().getCommandMeta(CMD_BIND) == null) {
            CommandRegistry.getInstance().register(new BindController(sessionManager, router));
            CommandRegistry.getInstance().register(new PlayerOpsController(router));
        }

        port = availablePort();
        NetworkTcpServerConfig config = new NetworkTcpServerConfig(port);
        config.setHost("127.0.0.1");
        server = new NettyTcpServer(config, new GameHandler(sessionManager, router),
                new ServerConnectionIdGenerator(100), new CustomTcpPipelineConfigurator(0));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void playerCommandBeforeLoginIsRejected() throws Exception {
        try (GameClient client = newClient()) {
            BlockingQueue<ClientResponse> received = attachQueue(client);
            int seq = client.send(CMD_ECHO, heartbeatReq());

            ClientResponse resp = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(resp, "未登陆应收到错误回包而不是被无声丢弃");
            assertEquals(CMD_ECHO, resp.getCommand());
            assertEquals(seq, resp.getSeq());
            assertEquals(GameErrorCode.NOT_LOGGED_IN, resp.getCode());
        }
    }

    @Test
    void unknownCommandGetsErrorFrame() throws Exception {
        try (GameClient client = newClient()) {
            BlockingQueue<ClientResponse> received = attachQueue(client);
            client.sendRaw(CMD_UNKNOWN, 77, 0, (byte) 0, new byte[0]);

            ClientResponse resp = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(CMD_UNKNOWN, resp.getCommand());
            assertEquals(77, resp.getSeq());
            assertEquals(GameErrorCode.UNKNOWN_COMMAND, resp.getCode());
        }
    }

    @Test
    void malformedBodyGetsBadRequest() throws Exception {
        try (GameClient client = newClient()) {
            BlockingQueue<ClientResponse> received = attachQueue(client);
            client.sendRaw(CMD_ECHO, 88, 0, (byte) 0, new byte[]{1, 2, 3});

            ClientResponse resp = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(CMD_ECHO, resp.getCommand());
            assertEquals(88, resp.getSeq());
            assertEquals(GameErrorCode.BAD_REQUEST, resp.getCode());
        }
    }

    @Test
    void bindThenPlayerCommandRoundTrips() throws Exception {
        try (GameClient client = newClient()) {
            BlockingQueue<ClientResponse> received = attachQueue(client);
            bind(client, received, 7001L);

            HeartbeatReq req = heartbeatReq();
            int seq = client.send(CMD_ECHO, req);
            ClientResponse resp = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(seq, resp.getSeq());
            assertEquals(GameErrorCode.OK, resp.getCode());
            assertEquals(req.getTime(), resp.decodeBody(HeartbeatRes.class).getTime());
        }
    }

    @Test
    void businessExceptionRepliesItsCode() throws Exception {
        try (GameClient client = newClient()) {
            BlockingQueue<ClientResponse> received = attachQueue(client);
            bind(client, received, 7002L);

            int seq = client.send(CMD_REJECT, heartbeatReq());
            ClientResponse resp = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(resp, "业务拒绝应回错误码");
            assertEquals(CMD_REJECT, resp.getCommand());
            assertEquals(seq, resp.getSeq());
            assertEquals(GameErrorCode.BAD_REQUEST, resp.getCode());
        }
    }

    @Test
    void handlerCrashRepliesInternalError() throws Exception {
        try (GameClient client = newClient()) {
            BlockingQueue<ClientResponse> received = attachQueue(client);
            bind(client, received, 7003L);

            int seq = client.send(CMD_CRASH, heartbeatReq());
            ClientResponse resp = received.poll(3, TimeUnit.SECONDS);
            assertNotNull(resp, "handler 崩溃应回 INTERNAL_ERROR 而不是让客户端干等");
            assertEquals(seq, resp.getSeq());
            assertEquals(GameErrorCode.INTERNAL_ERROR, resp.getCode());
        }
    }

    @Test
    void duplicateLoginKicksOldConnectionWithReason() throws Exception {
        try (GameClient first = newClient(); GameClient second = newClient()) {
            BlockingQueue<ClientResponse> firstReceived = attachQueue(first);
            BlockingQueue<ClientResponse> secondReceived = attachQueue(second);
            bind(first, firstReceived, 7004L);
            bind(second, secondReceived, 7004L);

            // 旧连接先收到踢下线推送（seq=0、code=顶号原因），随后被服务端关闭
            ClientResponse kick = firstReceived.poll(3, TimeUnit.SECONDS);
            assertNotNull(kick, "被顶号的旧连接应收到踢下线推送");
            assertEquals(KickConstant.COMMAND, kick.getCommand());
            assertEquals(0, kick.getSeq());
            assertEquals(GameErrorCode.DUPLICATE_LOGIN, kick.getCode());
            assertTrue(waitUntilInactive(first, 5_000), "被顶号的旧连接应被服务端关闭");

            // 新连接不受影响，业务照常
            int seq = second.send(CMD_ECHO, heartbeatReq());
            ClientResponse resp = secondReceived.poll(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(seq, resp.getSeq());
            assertEquals(GameErrorCode.OK, resp.getCode());
        }
    }

    @Test
    void idleConnectionIsKicked() throws Exception {
        // 单独起一个 1 秒读空闲阈值的服务端，验证心跳断供会被踢
        int idlePort = availablePort();
        NetworkTcpServerConfig config = new NetworkTcpServerConfig(idlePort);
        config.setHost("127.0.0.1");
        NettyTcpServer idleServer = new NettyTcpServer(config, new GameHandler(sessionManager, router),
                new ServerConnectionIdGenerator(200), new CustomTcpPipelineConfigurator(1));
        try {
            idleServer.start();
            try (GameClient client = new GameClient("127.0.0.1", idlePort)) {
                client.connect();
                assertTrue(client.isActive());
                assertTrue(waitUntilInactive(client, 5_000), "读空闲超时的连接应被服务端踢掉");
            }
        } finally {
            idleServer.stop();
        }
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no available port", e);
        }
    }

    private static void registerGroupIfAbsent(byte group, String name) {
        if (ExecutorGroupRegistry.getInstance().get(group) == null) {
            ExecutorGroupRegistry.getInstance().register(
                    DefaultExecutorGroup.virtualThreads(group, name, 2, 1_000));
        }
    }

    private static GameClient newClient() throws InterruptedException {
        return new GameClient("127.0.0.1", port).connect();
    }

    private static BlockingQueue<ClientResponse> attachQueue(GameClient client) {
        BlockingQueue<ClientResponse> queue = new LinkedBlockingQueue<>();
        client.onResponse(queue::add);
        return queue;
    }

    /** 用测试绑定命令完成“登陆”，断言拿到 OK 回包后返回。 */
    private static void bind(GameClient client, BlockingQueue<ClientResponse> received, long userId)
            throws InterruptedException {
        LoginReq req = new LoginReq();
        req.setUserId(userId);
        req.setToken("it");
        int seq = client.send(CMD_BIND, req);
        ClientResponse resp = received.poll(3, TimeUnit.SECONDS);
        assertNotNull(resp, "绑定命令应有回包");
        assertEquals(seq, resp.getSeq());
        assertEquals(GameErrorCode.OK, resp.getCode());
        assertEquals(userId, resp.decodeBody(LoginRes.class).getRoleId());
    }

    private static HeartbeatReq heartbeatReq() {
        HeartbeatReq req = new HeartbeatReq();
        req.setTime(System.currentTimeMillis());
        return req;
    }

    private static boolean waitUntilInactive(GameClient client, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!client.isActive()) {
                return true;
            }
            Thread.sleep(50);
        }
        return !client.isActive();
    }
}
