package cn.managame.demo.client;


import cn.managame.demo.network.GameProtocol;
import cn.managame.demo.protocol.CommandBinding;
import cn.managame.demo.protocol.client.ClientProtocol.ErrorRes;
import cn.managame.demo.protocol.GameCallException;
import cn.managame.demo.protocol.GamePacket;
import cn.managame.demo.protocol.client.ClientCommands;
import cn.managame.demo.serialization.MessageSerializer;
import cn.managame.network.ConnectCallback;
import cn.managame.network.Connection;
import cn.managame.network.NetworkHandler;
import cn.managame.network.netty.transport.TcpNetworkClient;
import io.netty.channel.ChannelOption;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.Consumer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Direct TCP client: response correlation and one-way notification dispatch are independent. */
public final class DemoClient implements AutoCloseable {
    private record Pending<S>(int command, Class<S> responseType, CompletableFuture<S> future) {
        void complete(GamePacket packet) {
            if (packet.code() == 0) future.complete(MessageSerializer.deserialize(packet.body(), responseType));
            else future.completeExceptionally(new GameCallException(packet.code(),
                    MessageSerializer.deserialize(packet.body(), ErrorRes.class).args()));
        }
    }
    private static final System.Logger LOG = System.getLogger(DemoClient.class.getName());
    private record NotifyHandler<T>(Class<T> type, Consumer<T> consumer) {
        void accept(Object message) { consumer.accept(type.cast(message)); }
    }
    private final ConcurrentHashMap<Integer, NotifyHandler<?>> notifyHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Pending<?>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger requestIdCounter = new AtomicInteger();
    private volatile Connection connection;
    private final TcpNetworkClient network = TcpNetworkClient.builder()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .pipeline(GameProtocol::pipeline).handlerFactory(() -> new NetworkHandler() {
                @Override public void onMessage(Connection source, Object message) {
                    var packet = (GamePacket) message;
                    if (packet.isNotify()) {
                        receiveNotify(packet);
                        return;
                    }
                    if (!packet.isResponse()) throw new IllegalArgumentException("Game client expects response or notify packets");
                    Pending<?> call = pending.get(packet.requestId());
                    if (call == null) return;
                    if (packet.command() != call.command()) throw new IllegalArgumentException("Response command differs from request");
                    // Decode before removing, so a malformed response is failed by onException too.
                    call.complete(packet);
                    pending.remove(packet.requestId(), call);
                }
                @Override public void onDisconnected(Connection source) { failPending(new ClosedChannelException()); }
                @Override public void onException(Connection source, Throwable cause) {
                    failPending(cause);
                    source.close();
                }
            }).build();

    /**
     * Register one handler per notification type, preferably before connecting.
     * Runs on the connection's I/O thread: return promptly. Handler exceptions are logged.
     */
    public <T> void onNotify(Class<T> type, Consumer<T> handler) {
        int command = ClientCommands.notificationId(Objects.requireNonNull(type));
        if (notifyHandlers.putIfAbsent(command, new NotifyHandler<>(type, Objects.requireNonNull(handler))) != null)
            throw new IllegalStateException("Notification handler already registered: " + type.getName());
    }

    private void receiveNotify(GamePacket packet) {
        Class<?> type = ClientCommands.NOTIFICATIONS.get(packet.command());
        if (type == null) throw new IllegalArgumentException("Unknown notification command: " + packet.command());
        // Validate the wire body even when the application has not subscribed.
        Object message = MessageSerializer.deserialize(packet.body(), type);
        var handler = notifyHandlers.get(packet.command());
        if (handler == null) return;
        try { handler.accept(message); }
        catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Notification handler failed: " + packet.command(), failure);
        }
    }

    public void connect(InetSocketAddress address) throws Exception {
        if (connection != null) throw new IllegalStateException("Already connected");
        network.init();
        CompletableFuture<Void> connected = new CompletableFuture<>();
        network.connect(address.getHostString(), address.getPort(), new ConnectCallback() {
            @Override public void onSuccess(Connection value) {
                connection = value;
                connected.complete(null);
            }
            @Override public void onFailure(Throwable failure) { connected.completeExceptionally(failure); }
        });
        connected.get(5, TimeUnit.SECONDS);
    }

    public <Q, S> CompletableFuture<S> call(CommandBinding<Q, S> binding, Q request) {
        Connection target = connection;
        if (target == null || !target.isActive()) return CompletableFuture.failedFuture(new ClosedChannelException());
        int requestId = requestIdCounter.incrementAndGet();
        if (requestId <= 0) return CompletableFuture.failedFuture(new IllegalStateException("Request IDs exhausted"));
        CompletableFuture<S> future = new CompletableFuture<>();
        Pending<S> call = new Pending<>(binding.id(), binding.responseType(), future);
        pending.put(requestId, call);
        future.orTimeout(5, TimeUnit.SECONDS).whenComplete((value, error) -> pending.remove(requestId, call));
        try {
            byte[] body = MessageSerializer.serialize(binding.requestType().cast(request));
            if (!GameProtocol.write(target, new GamePacket(binding.id(), requestId, 0, GamePacket.REQUEST, body)))
                future.completeExceptionally(new ClosedChannelException());
        } catch (RuntimeException failure) {
            future.completeExceptionally(failure);
        }
        return future;
    }

    private void failPending(Throwable failure) {
        pending.forEach((id, call) -> call.future().completeExceptionally(failure));
    }

    @Override public void close() {
        try { network.destroy(); }
        finally { failPending(new ClosedChannelException()); }
    }
}
