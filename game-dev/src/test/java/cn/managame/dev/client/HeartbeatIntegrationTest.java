package cn.managame.dev.client;

import cn.managame.dev.message.HeartbeatReq;
import cn.managame.dev.message.HeartbeatRes;
import cn.managame.dev.bootstrap.ForyMessageRegistrar;
import cn.managame.dev.bus.role.RoleController;
import cn.managame.dev.server.CustomTcpPipelineConfigurator;
import cn.managame.dev.protocol.GamePacket;
import cn.managame.dev.protocol.GamePacketConstant;
import cn.managame.dev.protocol.HeartbeatConstant;
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

        CustomTcpPipelineConfigurator serverPipeline = new CustomTcpPipelineConfigurator();
        NettyServer server = NettyServer.tcp(new IConnectionHandler() {
            @Override
            public void onConnect(IConnection connection) {
            }

            @Override
            public void onMessage(IConnection connection, Object packet) {
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
