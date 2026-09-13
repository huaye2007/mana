package cn.managame.demo.server.network;

import cn.managame.demo.network.GameProtocol;
import cn.managame.demo.protocol.GamePacket;
import cn.managame.demo.protocol.client.ClientCommands;
import cn.managame.demo.protocol.CommandBinding;
import cn.managame.demo.protocol.client.ClientProtocol.PlayerRequest;
import cn.managame.demo.serialization.MessageSerializer;
import cn.managame.demo.server.runtime.ServerRuntime;
import cn.managame.demo.server.support.CommandMetadata;
import cn.managame.demo.server.support.GameMessages;
import cn.managame.network.Connection;
import cn.managame.network.NetworkHandler;
import cn.managame.network.netty.transport.TcpNetworkServer;
import cn.managame.rpc.protocol.RpcError;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import cn.managame.demo.protocol.client.ClientProtocol.ErrorRes;
import static cn.managame.demo.protocol.client.ClientProtocol.INVALID_REQUEST;

/** Client TCP transport and protocol-table dispatch; contains no operation-specific logic. */
public final class GameTcpServer implements AutoCloseable {
    private final TcpNetworkServer server;
    private final ServerRuntime runtime;
    private final Map<Integer, CommandBinding<?, ?>> routes;
    private final GameMessages.Sender sender = new GameMessages.Sender() {
        @Override public boolean send(Connection connection, int requestId, int command, Object message) {
            var route = routes.get(command);
            if (route == null) throw new IllegalArgumentException("Unknown response command: " + command);
            byte[] body = MessageSerializer.serialize(route.responseType().cast(message));
            return GameProtocol.write(connection, new GamePacket(command, requestId, 0, GamePacket.RESPONSE, body));
        }
        @Override public boolean notify(Connection connection, Object message) {
            int command = ClientCommands.notificationId(message.getClass());
            byte[] body = MessageSerializer.serialize(message);
            return GameProtocol.write(connection, new GamePacket(command, 0, 0, GamePacket.NOTIFY, body));
        }
        @Override public boolean sendError(Connection connection, int requestId, int command, int code, String... args) {
            byte[] body = MessageSerializer.serialize(new ErrorRes(List.of(args)));
            return GameProtocol.write(connection, new GamePacket(command, requestId, code, GamePacket.RESPONSE, body));
        }
    };

    public GameTcpServer(int port, ServerRuntime runtime, Collection<CommandBinding<?, ?>> routes) {
        this.routes = routes.stream().collect(Collectors.toUnmodifiableMap(CommandBinding::id, route -> route));
        for (var route : this.routes.values()) {
            var command = runtime.command(route.id());
            if (route.id() <= 0 || !route.equals(command) || !PlayerRequest.class.isAssignableFrom(route.requestType()))
                throw new IllegalArgumentException("TCP route does not match runtime command: " + route.id());
        }
        this.runtime = runtime;
        server = TcpNetworkServer.builder().listen("127.0.0.1", port)
                .pipeline(GameProtocol::pipeline).handlerFactory(() -> new NetworkHandler() {
                    @Override public void onConnected(Connection connection) { GameMessages.bind(connection, sender); }
                    @Override public void onMessage(Connection connection, Object message) { receive(connection, (GamePacket) message); }
                }).build();
    }

    private void receive(Connection connection, GamePacket packet) {
        if (packet.flags() != GamePacket.REQUEST) throw new IllegalArgumentException("Game server expects request packets");
        var route = routes.get(packet.command());
        if (route == null) {
            sender.sendError(connection, packet.requestId(), packet.command(), RpcError.NO_HANDLER.code());
            return;
        }
        PlayerRequest request;
        try {
            request = (PlayerRequest) MessageSerializer.deserialize(packet.body(), route.requestType());
            if (request.playerId() <= 0) throw new IllegalArgumentException("Player ID must be positive");
        } catch (IllegalArgumentException failure) {
            sender.sendError(connection, packet.requestId(), packet.command(), INVALID_REQUEST);
            return;
        }
        runtime.submit(packet.command(), request, connection, packet.requestId(),
                CommandMetadata.player(request.playerId(), request.traceId()));
    }

    public void start() { server.start(); }
    public InetSocketAddress address() { return server.boundAddresses().values().iterator().next(); }
    @Override public void close() { server.stop(); }
}
