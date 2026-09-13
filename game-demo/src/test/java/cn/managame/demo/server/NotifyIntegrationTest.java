package cn.managame.demo.server;

import cn.managame.demo.client.DemoClient;
import cn.managame.demo.protocol.client.ClientCommands;
import cn.managame.demo.server.network.GameTcpServer;
import cn.managame.demo.server.runtime.ServerRuntime;
import cn.managame.demo.server.support.GameMessages;
import cn.managame.demo.server.support.PlayerId;
import cn.managame.demo.server.support.PlayerRoute;
import cn.managame.network.Connection;
import cn.managame.runtime.context.CommandContext;
import cn.managame.runtime.execution.HandlerContexts;
import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.net.Socket;
import java.io.DataOutputStream;
import cn.managame.demo.serialization.MessageSerializer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.managame.demo.protocol.client.ClientProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class NotifyIntegrationTest {
    @Handler(routeType = PlayerRoute.class)
    public static final class NotifyHandler {
        final LinkedBlockingQueue<Connection> connections = new LinkedBlockingQueue<>();

        @HandlerMethod public void wallet(GetWalletReq request, PlayerId player) {
            connections.add((Connection) ((CommandContext) HandlerContexts.current()).connection());
            GameMessages.notify(new WalletChangedNotify(player.value(), 100));
            GameMessages.send(new GetWalletRes(player.value(), 90));
            GameMessages.notify(new WalletChangedNotify(player.value(), 90));
        }
    }

    @Test void notificationsBeforeAndAfterResponseDoNotConsumePendingCall() throws Exception {
        var handler = new NotifyHandler();
        var received = new LinkedBlockingQueue<WalletChangedNotify>();
        try (var runtime = new ServerRuntime(20, List.of(ClientCommands.WALLET), handler);
             var server = new GameTcpServer(0, runtime, walletRoutes());
             var client = new DemoClient()) {
            client.onNotify(WalletChangedNotify.class, received::add);
            server.start();
            client.connect(server.address());
            assertEquals(new GetWalletRes(7, 90), client.call(ClientCommands.WALLET,
                    new GetWalletReq(7, 1)).get(3, TimeUnit.SECONDS));
            assertEquals(new WalletChangedNotify(7, 100), received.poll(3, TimeUnit.SECONDS));
            assertEquals(new WalletChangedNotify(7, 90), received.poll(3, TimeUnit.SECONDS));

            // No current command or pending client call is needed for a later push.
            Connection connection = handler.connections.poll(3, TimeUnit.SECONDS);
            assertNotNull(connection);
            assertTrue(GameMessages.notify(connection, new WalletChangedNotify(7, 80)));
            assertEquals(new WalletChangedNotify(7, 80), received.poll(3, TimeUnit.SECONDS));
        }
    }

    @Test void callbackFailureDoesNotBreakResponsesOrLaterNotifications() throws Exception {
        var received = new LinkedBlockingQueue<WalletChangedNotify>();
        var count = new AtomicInteger();
        try (var runtime = new ServerRuntime(20, List.of(ClientCommands.WALLET), new NotifyHandler());
             var server = new GameTcpServer(0, runtime, walletRoutes());
             var client = new DemoClient()) {
            client.onNotify(WalletChangedNotify.class, message -> {
                if (count.incrementAndGet() == 1) throw new IllegalStateException("Expected application callback failure");
                received.add(message);
            });
            server.start();
            client.connect(server.address());
            assertEquals(new GetWalletRes(7, 90), client.call(ClientCommands.WALLET,
                    new GetWalletReq(7, 1)).get(3, TimeUnit.SECONDS));
            assertEquals(new WalletChangedNotify(7, 90), received.poll(3, TimeUnit.SECONDS));
            assertEquals(new GetWalletRes(8, 90), client.call(ClientCommands.WALLET,
                    new GetWalletReq(8, 2)).get(3, TimeUnit.SECONDS));
            assertEquals(new WalletChangedNotify(8, 100), received.poll(3, TimeUnit.SECONDS));
            assertEquals(new WalletChangedNotify(8, 90), received.poll(3, TimeUnit.SECONDS));
            assertEquals(4, count.get());
        }
    }

    @Test void validUnsubscribedNotificationsAreDiscarded() throws Exception {
        try (var runtime = new ServerRuntime(20, List.of(ClientCommands.WALLET), new NotifyHandler());
             var server = new GameTcpServer(0, runtime, walletRoutes());
             var client = new DemoClient()) {
            server.start();
            client.connect(server.address());
            for (long player : new long[] { 7, 8 }) {
                assertEquals(new GetWalletRes(player, 90), client.call(ClientCommands.WALLET,
                        new GetWalletReq(player, 1)).get(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test void notifyApiRejectsWrongTypesAndMissingContextBeforeSending() throws Exception {
        var handler = new NotifyHandler();
        try (var runtime = new ServerRuntime(20, List.of(ClientCommands.WALLET), handler);
             var server = new GameTcpServer(0, runtime, walletRoutes());
             var client = new DemoClient()) {
            assertThrows(IllegalArgumentException.class, () -> client.onNotify(GetWalletRes.class, ignored -> {}));
            client.onNotify(WalletChangedNotify.class, ignored -> {});
            assertThrows(IllegalStateException.class, () -> client.onNotify(WalletChangedNotify.class, ignored -> {}));
            assertThrows(IllegalStateException.class, () -> GameMessages.notify(new WalletChangedNotify(7, 100)));
            server.start();
            client.connect(server.address());
            client.call(ClientCommands.WALLET, new GetWalletReq(7, 1)).get(3, TimeUnit.SECONDS);
            Connection connection = handler.connections.poll(3, TimeUnit.SECONDS);
            assertNotNull(connection);
            assertThrows(IllegalArgumentException.class, () -> GameMessages.notify(connection, new GetWalletRes(7, 90)));
            assertEquals(new GetWalletRes(7, 90), client.call(ClientCommands.WALLET,
                    new GetWalletReq(7, 2)).get(3, TimeUnit.SECONDS));
        }
    }

    @Test void clientCannotSendServerNotificationsAsRequests() throws Exception {
        try (var runtime = new ServerRuntime(20, List.of(ClientCommands.WALLET), new NotifyHandler());
             var server = new GameTcpServer(0, runtime, walletRoutes())) {
            server.start();
            try (var socket = new Socket(server.address().getAddress(), server.address().getPort())) {
                socket.setSoTimeout(3000);
                var output = new DataOutputStream(socket.getOutputStream());
                byte[] body = MessageSerializer.serialize(new WalletChangedNotify(7, 90));
                output.writeInt(16 + body.length);
                output.writeInt(WALLET_CHANGED);
                output.writeInt(0);
                output.writeInt(0);
                output.writeInt(2);
                output.write(body);
                output.flush();
                assertEquals(-1, socket.getInputStream().read());
            }
        }
    }

    private static List<cn.managame.demo.protocol.CommandBinding<?, ?>> walletRoutes() {
        return ServerIngress.TCP.stream().filter(route -> route.id() == GET_WALLET).toList();
    }
}
