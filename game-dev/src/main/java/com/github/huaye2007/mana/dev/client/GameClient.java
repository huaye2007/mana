package com.github.huaye2007.mana.dev.client;

import com.github.huaye2007.mana.dev.message.HeartbeatReq;
import com.github.huaye2007.mana.dev.message.LoginReq;
import com.github.huaye2007.mana.dev.protocol.GamePacket;
import com.github.huaye2007.mana.dev.protocol.GamePacketConstant;
import com.github.huaye2007.mana.dev.protocol.GamePacketEncoder;
import com.github.huaye2007.mana.dev.protocol.HeartbeatConstant;
import com.github.huaye2007.mana.serialization.ISerializer;
import com.github.huaye2007.mana.serialization.SerializerManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 外网 TCP 测试客户端：连服务端、发业务消息、收回包。
 *
 * <p>与服务端共用同一套外网帧格式和 Fury 序列化（直接复用 {@link GamePacketEncoder} 和
 * {@link SerializerManager} 的 Fury 实例），保证编解码与线上一致，省去自己拼字节。</p>
 *
 * <p>典型用法：
 * <pre>{@code
 * try (GameClient client = new GameClient("127.0.0.1", 8080)) {
 *     client.onResponse(resp -> System.out.println("收到: " + resp));
 *     client.connect();
 *     client.login(10001L, "test-token");   // command=1000
 *     // ...发别的业务命令...
 * }
 * }</pre>
 *
 * <p>回包通过 {@link #onResponse(Consumer)} 注册的回调投递（在 IO 线程上执行，回调里别阻塞），
 * 不引入 CompletableFuture——异步结果一律走回调风格。</p>
 */
public class GameClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(GameClient.class);

    /** LoginController 上 {@code @GameMethod(value = 1000)} 对应的登陆 command。 */
    public static final int CMD_LOGIN = 1000;

    private final String host;
    private final int port;
    private final ISerializer serializer =
            SerializerManager.getInstance().getISerializer(GamePacketConstant.BODY_SERIAL_TYPE);
    private final AtomicInteger seqGenerator = new AtomicInteger();

    private volatile Consumer<ClientResponse> responseConsumer = resp -> logger.info("response: {}", resp);
    private EventLoopGroup group;
    private Channel channel;

    public GameClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * 注册回包回调。可在 {@link #connect()} 前后任意时刻调用；handler 每次都读最新值。
     * 回调在 Netty IO 线程执行，实现应保持轻量。
     */
    public GameClient onResponse(Consumer<ClientResponse> consumer) {
        this.responseConsumer = consumer;
        return this;
    }

    /** 建立连接，阻塞直到 TCP 连上（或抛异常）。 */
    public synchronized GameClient connect() throws InterruptedException {
        if (channel != null && channel.isActive()) {
            return this;
        }
        group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new GamePacketEncoder());
                        ch.pipeline().addLast(new ClientFrameDecoder());
                        // handler 读 GameClient.responseConsumer 的当前值，支持连接后再换回调
                        ch.pipeline().addLast(new ClientFrameHandler(resp -> responseConsumer.accept(resp)));
                    }
                });
        channel = bootstrap.connect(host, port).sync().channel();
        logger.info("connected to {}:{}", host, port);
        return this;
    }

    /**
     * 发送一帧心跳（command=1001，body=HeartbeatReq，带当前时间戳）。
     * 返回该帧的 seq，可用来跟回包的 HeartbeatRes 对上。
     */
    public int heartbeat() {
        HeartbeatReq req = new HeartbeatReq();
        req.setTime(System.currentTimeMillis());
        return send(HeartbeatConstant.COMMAND, req);
    }

    /** 发送登陆消息（command=1000，body=LoginReq）。返回该消息的 seq。 */
    public int login(long userId, String token, int serverId) {
        LoginReq req = new LoginReq();
        req.setUserId(userId);
        req.setToken(token);
        req.setServerId(serverId);
        return send(CMD_LOGIN, req);
    }

    /**
     * 发送一条业务消息：自动分配 seq、code=0、flags=0，body 用 Fury 序列化。
     *
     * @param command 业务命令号（对应服务端 {@code @GameMethod} 的 value）
     * @param body    业务参数对象，可为 {@code null}（表示空 body）
     * @return 本条消息分配到的 seq，便于把回包按 seq 对上请求
     */
    public int send(int command, Object body) {
        int seq = seqGenerator.incrementAndGet();
        send(command, seq, 0, (byte) 0, body);
        return seq;
    }

    /** 完全指定帧头字段地发送，body 用 Fury 序列化（{@code null} 视为空 body）。 */
    public void send(int command, int seq, int code, byte flags, Object body) {
        byte[] bodyBytes = body == null ? new byte[0] : serializer.serialize(body);
        sendRaw(command, seq, code, flags, bodyBytes);
    }

    /** 直接发送已序列化好的 body 字节，适合构造异常/边界帧做测试。 */
    public void sendRaw(int command, int seq, int code, byte flags, byte[] bodyBytes) {
        Channel ch = this.channel;
        if (ch == null || !ch.isActive()) {
            throw new IllegalStateException("client not connected");
        }
        GamePacket packet = new GamePacket();
        packet.setCommand(command);
        packet.setSeq(seq);
        packet.setCode(code);
        packet.setFlags(flags);
        packet.setBody(bodyBytes);
        ch.writeAndFlush(packet).addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                logger.warn("send failed, command={}, seq={}", command, seq, f.cause());
            }
        });
    }

    /** 当前连接是否仍存活。 */
    public boolean isActive() {
        Channel ch = this.channel;
        return ch != null && ch.isActive();
    }

    @Override
    public synchronized void close() {
        try {
            if (channel != null) {
                channel.close().syncUninterruptibly();
                channel = null;
            }
        } finally {
            if (group != null) {
                group.shutdownGracefully().syncUninterruptibly();
                group = null;
            }
        }
        logger.info("client closed");
    }
}
