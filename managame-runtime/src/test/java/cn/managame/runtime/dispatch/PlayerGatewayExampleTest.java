package cn.managame.runtime.dispatch;

import static cn.managame.FrameworkErrorCodes.INVALID_ROUTE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.managame.Metadata;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.netty.ConnectionInitializer;
import cn.managame.network.netty.NetworkHandlers;
import cn.managame.network.netty.NetworkServer;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.annotation.RouteKey;
import cn.managame.runtime.executor.RouteExecutors;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.AttributeKey;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * How an application receives player requests: its own packet format, its own session per connection, and the
 * framework's Dispatcher only to run each message on its handler. Here a packet is the text
 * {@code seq|type|payload}, length-prefixed; answers are {@code seq|ok|result} or {@code seq|err|code}.
 */
class PlayerGatewayExampleTest {
    private static final int PLAYER = 1, LOGIN = 2;
    private static final byte ROLE = 1;
    private static final AttributeKey<Session> SESSION = AttributeKey.valueOf("example.session");

    // The application's messages
    record Login(@RouteKey(LOGIN) long roleId) {}
    record Buy(int item) {}
    record Logout() {}

    /** The application's session: the connection and, once logged in, the role it acts for. */
    record Session(Connection connection, long roleId) implements Context {
        @Override public int domain() { return 0; }
        @Override public long routeKey() { return 0; }
        @Override public byte businessType() { return roleId == 0 ? 0 : ROLE; }
        @Override public long businessId() { return roleId; }
        @Override public Metadata metadata() { return Metadata.empty(); }
    }

    @Domain(LOGIN)
    static class Accounts {
        @HandleMethod String login(HandleContext context, Login login) {
            Connection connection = ((Session) context.source()).connection();
            connection.set(SESSION, new Session(connection, login.roleId()));
            return "welcome " + login.roleId();
        }
    }

    @Domain(PLAYER)
    class Shop {
        @HandleMethod String buy(Buy buy) { return "role " + Context.current().businessId() + " bought " + buy.item(); }
        @HandleMethod void logout(Logout logout) { loggedOut.add(Context.current().businessId()); }
    }

    /** The application's ConnectionHandler: it decodes packets, keeps the session and writes answers. */
    record Gateway(Dispatcher dispatcher) implements ConnectionHandler {
        @Override public void onActive(Connection connection) { connection.set(SESSION, new Session(connection, 0)); }

        @Override public void onRead(Connection connection, Object message) {
            String[] packet = ((String) message).split("\\|", 3);
            String seq = packet[0];
            Object body = switch (packet[1]) {
                case "login" -> new Login(Long.parseLong(packet[2]));
                case "buy" -> new Buy(Integer.parseInt(packet[2]));
                default -> throw new IllegalArgumentException("Unknown packet type " + packet[1]);
            };
            dispatcher.dispatch(connection.get(SESSION), body,
                    result -> connection.write(seq + "|ok|" + result),
                    errorCode -> connection.write(seq + "|err|" + errorCode));
        }

        @Override public void onInActive(Connection connection) {
            Session session = connection.get(SESSION);
            if (session.roleId() != 0) dispatcher.dispatch(session, new Logout()); // after the role's pending requests
        }

        @Override public void onException(Connection connection, Throwable cause) { connection.close(); }
    }

    private final GameRuntime runtime = GameRuntime.builder()
            .domain(PLAYER, RouteExecutors.virtualThreads(), (source, message) -> {
                if (source.businessId() == 0) throw new IllegalArgumentException("Not logged in");
                return RouteKeys.businessId().routeKey(source, message);
            })
            .domain(LOGIN, RouteExecutors.striped(2)).build();
    private final BlockingQueue<Long> loggedOut = new LinkedBlockingQueue<>();
    private final NetworkServer server = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
            ConnectionInitializer.tcp()
                    .addLast(NetworkHandlers.lengthFieldFraming(1024))
                    .addLast("string-decoder", new StringDecoder(StandardCharsets.UTF_8))
                    .addLast("string-encoder", new StringEncoder(StandardCharsets.UTF_8))
                    .handler(new Gateway(Dispatcher.builder(runtime).register(new Accounts(), new Shop()).build()))
                    .build());

    @AfterEach void stop() {
        server.close();
        runtime.close();
    }

    @Test void requestsActForTheRoleTheSessionLoggedIn() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(out, "1|buy|5");
            assertEquals("1|err|" + INVALID_ROUTE_KEY, receive(in), "nobody to act for yet");
            send(out, "2|login|1001");
            assertEquals("2|ok|welcome 1001", receive(in));
            for (int item = 3; item <= 5; item++) send(out, item + "|buy|" + item);
            for (int item = 3; item <= 5; item++) assertEquals(item + "|ok|role 1001 bought " + item, receive(in));
        }
        assertEquals(1001L, loggedOut.poll(5, TimeUnit.SECONDS));
    }

    private static void send(DataOutputStream out, String packet) throws Exception {
        byte[] bytes = packet.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    private static String receive(DataInputStream in) throws Exception {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
