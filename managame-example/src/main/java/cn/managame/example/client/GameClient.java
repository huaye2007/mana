package cn.managame.example.client;

import cn.managame.example.GamePacket;
import cn.managame.example.GamePacketCodec;
import cn.managame.example.Protocols;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.netty.ConnectionInitializer;
import cn.managame.network.netty.NetworkClient;
import cn.managame.network.netty.NetworkHandlers;
import com.google.protobuf.Message;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 基于 managame-network 的游戏客户端：一条到服务器的 TCP 连接，断开后 NetworkClient 自动重连。{@link #request} 发消息
 * 并等它的应答，{@link #send} 发不要应答的消息，服务器的推送（seq 为 0）交给 onPush。登录之后，Protocols 里绑定过的
 * 任何消息都这样发。
 *
 * <p>应答和推送都在连接的 EventLoop 上到达，所以 onPush 和 future 的回调里不要阻塞；在自己的线程上 get / join 即可。
 * 重连后服务器看到的是一条新连接，需要重新登录。
 */
public final class GameClient implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(GameClient.class.getName());

    private final Protocols protocols;
    private final Consumer<GamePacket> onPush;
    private final AtomicInteger seqs = new AtomicInteger();
    private final Map<Integer, CompletableFuture<GamePacket>> waiting = new ConcurrentHashMap<>();
    private final CountDownLatch connectedOnce = new CountDownLatch(1);
    private volatile Connection connection;
    private final NetworkClient network;

    private GameClient(SocketAddress server, Protocols protocols, Consumer<GamePacket> onPush) {
        this.protocols = protocols;
        this.onPush = onPush;
        this.network = NetworkClient.connect(server, ConnectionInitializer.tcp()
                .addLast(NetworkHandlers.lengthFieldFraming(GamePacketCodec.MAX_FRAME_LENGTH))
                .addLast("game-codec", new GamePacketCodec(protocols))
                .handler(new Handler())
                .build());
    }

    /** 连上 server 后返回；timeout 内连不上时关闭客户端，抛 IllegalStateException。 */
    public static GameClient connect(SocketAddress server, Protocols protocols, Consumer<GamePacket> onPush, Duration timeout)
            throws InterruptedException {
        GameClient client = new GameClient(server, protocols, onPush);
        if (!client.connectedOnce.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            client.close();
            throw new IllegalStateException("连不上 " + server + "，已等 " + timeout);
        }
        return client;
    }

    /**
     * 发送 message 并等应答。future 以应答包完成：code 为 0 时 body 是应答消息，否则 code 是错误码；没有连接、写不出去
     * 或连接断开时异常完成。等不及时用 future 的 orTimeout。
     */
    public CompletableFuture<GamePacket> request(Message message) {
        Connection current = connection;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("没有连接"));
        int seq = seqs.incrementAndGet();
        CompletableFuture<GamePacket> answer = new CompletableFuture<>();
        waiting.put(seq, answer);
        answer.whenComplete((packet, failure) -> waiting.remove(seq));
        WriteStatus status = current.write(protocols.packet(seq, message));
        if (status != WriteStatus.ACCEPTED) answer.completeExceptionally(new IllegalStateException("没有发出去：" + status));
        return answer;
    }

    /** 发送不要应答的消息，seq 为 0。 */
    public WriteStatus send(Message message) {
        Connection current = connection;
        return current == null ? WriteStatus.INACTIVE : current.write(protocols.packet(0, message));
    }

    /** 不再重连，关闭当前连接；还在等的请求随连接断开而异常完成。 */
    @Override public void close() { network.close(); }

    private final class Handler implements ConnectionHandler {
        @Override public void onActive(Connection connected) {
            connection = connected;
            connectedOnce.countDown();
        }

        @Override public void onRead(Connection connected, Object message) {
            GamePacket packet = (GamePacket) message;
            if (packet.getSeq() == 0) {
                onPush.accept(packet);
                return;
            }
            CompletableFuture<GamePacket> answer = waiting.get(packet.getSeq());
            if (answer != null) answer.complete(packet);   // 没人等了（已超时）的应答丢掉
        }

        @Override public void onInActive(Connection closed) {
            connection = null;
            IOException lost = new IOException("连接已断开");
            waiting.values().forEach(answer -> answer.completeExceptionally(lost));
        }

        @Override public void onException(Connection connected, Throwable cause) {
            LOG.log(Level.WARNING, "连接 " + connected.remoteAddress() + " 出错，关闭", cause);
            connected.close();
        }
    }
}
