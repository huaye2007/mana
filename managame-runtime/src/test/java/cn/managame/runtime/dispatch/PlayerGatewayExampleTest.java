package cn.managame.runtime.dispatch;

import static cn.managame.FrameworkErrorCodes.INVALID_ROUTE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.network.netty.ConnectionInitializer;
import cn.managame.network.netty.NetworkHandlers;
import cn.managame.network.netty.NetworkServer;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.executor.RouteExecutors;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.AttributeKey;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * How an application receives player requests: its own packet format, its own session per connection, and the
 * framework's Dispatcher only to run each message on its handler. Here a packet is the text
 * {@code seq|type|payload}, length-prefixed; answers are {@code seq|ok|result} or {@code seq|err|code|args}, the
 * failure's args joined by commas.
 */
class PlayerGatewayExampleTest {
    private static final int PLAYER = 1, LOGIN = 2;
    private static final int SOLD_OUT = 10_001;   // the game's own code; its message shows the item
    private static final byte ROLE = 1;
    private static final AttributeKey<Session> SESSION = AttributeKey.valueOf("example.session");

    // The application's messages
    record Login(long roleId) {}
    record Buy(int item) {}
    record Logout() {}

    /**
     * The application's session, kept on its connection: the connection and, once logged in, the role it acts for. It
     * is no Context: the gateway builds each message's context from it.
     */
    record Session(Connection connection, long roleId) {}

    @Domain(LOGIN)
    static class Accounts {
        @HandleMethod(routeKey = "roleId") void login(HandleContext context, Login login) { context.reply("welcome " + login.roleId()); }
    }

    @Domain(PLAYER)
    class Shop {
        @HandleMethod void buy(HandleContext context, Buy buy) {
            if (buy.item() > 9) context.fail(SOLD_OUT, String.valueOf(buy.item()));
            else context.reply("role " + context.businessId() + " bought " + buy.item());
        }
        @HandleMethod void logout(Logout logout) { loggedOut.add(Context.current().businessId()); }
    }

    /**
     * The application's ConnectionHandler: it decodes packets, keeps the session, gives each message the context of
     * the role it acts for, and writes answers through the callbacks, which hold the connection and the packet's seq.
     */
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
            dispatcher.dispatch(context(connection.get(SESSION)), body,
                    result -> {
                        // a login answered: bind the role before the client hears of it, so its next packets act for it
                        if (body instanceof Login login) connection.set(SESSION, new Session(connection, login.roleId()));
                        connection.write(seq + "|ok|" + result);
                    },
                    (errorCode, args) -> connection.write(seq + "|err|" + errorCode + "|" + String.join(",", args)));
        }

        @Override public void onInActive(Connection connection) {
            Session session = connection.get(SESSION);
            if (session.roleId() != 0) dispatcher.dispatch(context(session), new Logout()); // after the role's pending requests
        }

        /** Whom a message from the session acts for: the role, on its own route, or nobody before login. */
        private static GameContext context(Session session) {
            long role = session.roleId();
            return role == 0 ? GameContext.of(LOGIN, 0) : GameContext.of(PLAYER, role, ROLE, role);
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
                    .handler(new Gateway(Dispatcher.builder(runtime).register(List.of(new Accounts(), new Shop())).build()))
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
            assertEquals("1|err|" + INVALID_ROUTE_KEY + "|", receive(in), "nobody to act for yet");
            send(out, "2|login|1001");
            assertEquals("2|ok|welcome 1001", receive(in));
            for (int item = 3; item <= 5; item++) send(out, item + "|buy|" + item);
            for (int item = 3; item <= 5; item++) assertEquals(item + "|ok|role 1001 bought " + item, receive(in));
            send(out, "6|buy|12");
            assertEquals("6|err|" + SOLD_OUT + "|12", receive(in), "the item travels with the code");
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
