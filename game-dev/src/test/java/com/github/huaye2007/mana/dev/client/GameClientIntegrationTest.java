package com.github.huaye2007.mana.dev.client;

import com.github.huaye2007.mana.dev.message.LoginReq;
import com.github.huaye2007.mana.dev.bootstrap.ForyMessageRegistrar;
import com.github.huaye2007.mana.dev.bus.login.LoginController;
import com.github.huaye2007.mana.dev.server.CustomTcpPipelineConfigurator;
import com.github.huaye2007.mana.dev.protocol.GamePacket;
import com.github.huaye2007.mana.dev.protocol.GamePacketConstant;
import com.github.huaye2007.mana.dev.server.PlayerSession;
import com.github.huaye2007.mana.network.connection.IConnection;
import com.github.huaye2007.mana.network.connection.ServerConnectionIdGenerator;
import com.github.huaye2007.mana.network.handler.INetworkHandler;
import com.github.huaye2007.mana.network.server.NettyTcpServer;
import com.github.huaye2007.mana.network.server.NetworkTcpServerConfig;
import com.github.huaye2007.mana.network.session.ISession;
import com.github.huaye2007.mana.runtime.command.CommandRegistry;
import com.github.huaye2007.mana.serialization.SerializerManager;
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
 * 用真实服务端管线（{@link CustomTcpPipelineConfigurator} 编解码）+ {@link GameClient} 跑端到端往返，
 * 验证客户端发出的帧能被服务端正确解出，且客户端能解出服务端回的帧——两个方向的线格式都对齐。
 */
class GameClientIntegrationTest {

    private static final int CMD_LOGIN = 1000;

    @Test
    void clientLoginRoundTripsThroughRealServerPipeline() throws Exception {
        // 服务端解码器要按 command 找到 body 类型，先把 command=1000 注册进去（进程级单例，幂等）
        if (CommandRegistry.getInstance().getCommandMeta(CMD_LOGIN) == null) {
            CommandRegistry.getInstance().register(new LoginController());
        }
        // 默认 Fory 要求类注册，收发同进程共用一套 Fory，登记一次覆盖请求与响应类型
        ForyMessageRegistrar.registerMessageTypes();

        BlockingQueue<GamePacket> serverReceived = new LinkedBlockingQueue<>();
        int port = availablePort();

        NetworkTcpServerConfig config = new NetworkTcpServerConfig(port);
        config.setHost("127.0.0.1");
        NettyTcpServer server = new NettyTcpServer(config, new INetworkHandler() {
            @Override
            public void onConnect(ISession session) {
            }

            @Override
            public void onMessage(ISession session, Object packet) {
                GamePacket in = (GamePacket) packet;
                serverReceived.add(in);
                // 原样回一帧：body 必须是 byte[]（GamePacketEncoder 的契约），把收到的 LoginReq 再序列化回去
                byte[] echoBody = SerializerManager.getInstance()
                        .getISerializer(GamePacketConstant.BODY_SERIAL_TYPE)
                        .serialize(in.getBody());
                GamePacket out = new GamePacket();
                out.setCommand(in.getCommand());
                out.setSeq(in.getSeq());
                out.setCode(0);
                out.setFlags((byte) 0);
                out.setBody(echoBody);
                session.writeMsg(out);
            }

            @Override
            public void onDisconnect(ISession session) {
            }

            @Override
            public void onException(ISession session, Throwable cause) {
            }

            @Override
            public ISession createSession(IConnection connection) {
                return new PlayerSession(connection);
            }
        }, new ServerConnectionIdGenerator(100), new CustomTcpPipelineConfigurator());

        BlockingQueue<ClientResponse> clientReceived = new LinkedBlockingQueue<>();
        try {
            server.start();

            try (GameClient client = new GameClient("127.0.0.1", port)) {
                client.onResponse(clientReceived::add);
                client.connect();
                int seq = client.login(42L, "tok",100);

                // 服务端方向：帧解出来，body 是类型化的 LoginReq
                GamePacket onServer = serverReceived.poll(3, TimeUnit.SECONDS);
                assertNotNull(onServer, "服务端应收到登陆帧");
                assertEquals(CMD_LOGIN, onServer.getCommand());
                assertEquals(seq, onServer.getSeq());
                assertTrue(onServer.getBody() instanceof LoginReq);
                assertEquals(42L, ((LoginReq) onServer.getBody()).getUserId());

                // 客户端方向：回帧解出来，按已知类型反序列化 body
                ClientResponse onClient = clientReceived.poll(3, TimeUnit.SECONDS);
                assertNotNull(onClient, "客户端应收到回帧");
                assertEquals(CMD_LOGIN, onClient.getCommand());
                assertEquals(seq, onClient.getSeq());
                LoginReq echoed = onClient.decodeBody(LoginReq.class);
                assertNotNull(echoed);
                assertEquals(42L, echoed.getUserId());
                assertEquals("tok", echoed.getToken());
            }
        } finally {
            server.stop();
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
