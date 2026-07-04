package com.github.huaye2007.mana.dev.client;

import com.github.huaye2007.mana.dev.message.HeartbeatReq;
import com.github.huaye2007.mana.dev.message.HeartbeatRes;
import com.github.huaye2007.mana.dev.bootstrap.ForyMessageRegistrar;
import com.github.huaye2007.mana.dev.bus.role.RoleController;
import com.github.huaye2007.mana.dev.server.CustomTcpPipelineConfigurator;
import com.github.huaye2007.mana.dev.protocol.GamePacket;
import com.github.huaye2007.mana.dev.protocol.GamePacketConstant;
import com.github.huaye2007.mana.dev.protocol.HeartbeatConstant;
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
 * 验证客户端 {@link GameClient#heartbeat()} 发出的帧：command=1001、body 是带时间戳的 {@link HeartbeatReq}，
 * 能被服务端按命令对应类型正确解出；并验证客户端能解出服务端回的 {@link HeartbeatRes}。
 * 只测客户端发送/回包解码这条链路，不拉起 PLAYER 执行器组与登陆绑定。
 */
class HeartbeatIntegrationTest {

    @Test
    void heartbeatSendsTypedReqAndDecodesRes() throws Exception {
        // 服务端解码器要按 command 找 body 类型，先把 command=1001 注册进去（进程级单例，幂等）
        if (CommandRegistry.getInstance().getCommandMeta(HeartbeatConstant.COMMAND) == null) {
            CommandRegistry.getInstance().register(new RoleController());
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
                // 回一帧 HeartbeatRes：time 回显请求里的 time，方便两个方向对上
                HeartbeatRes res = new HeartbeatRes();
                res.setTime(((HeartbeatReq) in.getBody()).getTime());
                GamePacket out = new GamePacket();
                out.setCommand(in.getCommand());
                out.setSeq(in.getSeq());
                out.setCode(0);
                out.setFlags((byte) 0);
                out.setBody(SerializerManager.getInstance()
                        .getISerializer(GamePacketConstant.BODY_SERIAL_TYPE).serialize(res));
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

                long before = System.currentTimeMillis();
                int seq = client.heartbeat();

                // 服务端方向：解出 command=1001 和带时间戳的 HeartbeatReq
                GamePacket onServer = serverReceived.poll(3, TimeUnit.SECONDS);
                assertNotNull(onServer, "服务端应收到心跳帧");
                assertEquals(HeartbeatConstant.COMMAND, onServer.getCommand());
                assertEquals(seq, onServer.getSeq());
                assertTrue(onServer.getBody() instanceof HeartbeatReq);
                long sentTime = ((HeartbeatReq) onServer.getBody()).getTime();
                assertTrue(sentTime >= before, "心跳应带发送时刻的时间戳");

                // 客户端方向：解出 HeartbeatRes
                ClientResponse onClient = clientReceived.poll(3, TimeUnit.SECONDS);
                assertNotNull(onClient, "客户端应收到 HeartbeatRes");
                assertEquals(HeartbeatConstant.COMMAND, onClient.getCommand());
                assertEquals(seq, onClient.getSeq());
                HeartbeatRes res = onClient.decodeBody(HeartbeatRes.class);
                assertNotNull(res);
                assertEquals(sentTime, res.getTime());
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
