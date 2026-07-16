package cn.managame.dev.client;

import cn.managame.dev.message.LoginReq;
import cn.managame.dev.bootstrap.ForyMessageRegistrar;
import cn.managame.dev.bus.login.LoginController;
import cn.managame.dev.server.CustomTcpPipelineConfigurator;
import cn.managame.dev.protocol.GamePacket;
import cn.managame.dev.protocol.GamePacketConstant;
import cn.managame.network.connection.IConnection;
import cn.managame.network.handler.IConnectionHandler;
import cn.managame.network.server.NettyServer;
import cn.managame.runtime.command.CommandRegistry;
import cn.managame.serialization.SerializerManager;
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

        CustomTcpPipelineConfigurator serverPipeline = new CustomTcpPipelineConfigurator();
        NettyServer server = NettyServer.tcp(new IConnectionHandler() {
            @Override
            public void onConnect(IConnection connection) {
            }

            @Override
            public void onMessage(IConnection connection, Object packet) {
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
                connection.writeMsg(out);
            }

            @Override
            public void onDisconnect(IConnection connection) {
            }

            @Override
            public void onException(IConnection connection, Throwable cause) {
                connection.close();
            }

            @Override
            public void onIdle(IConnection connection) {
            }
        }).bind("127.0.0.1", port).pipeline(serverPipeline::configure).build();

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
