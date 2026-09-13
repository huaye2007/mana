package cn.managame.demo.server.runtime;

import cn.managame.demo.server.gameplay.WalletHandler;
import cn.managame.demo.server.support.CommandMetadata;
import cn.managame.demo.server.support.GameMessages;
import cn.managame.demo.protocol.client.ClientCommands;
import cn.managame.network.AttributeKey;
import cn.managame.network.Connection;
import cn.managame.network.ConnectionType;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.runtime.context.CommandContext;
import cn.managame.runtime.execution.HandlerContexts;
import cn.managame.runtime.protocol.ProtocolType;
import org.junit.jupiter.api.Test;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.*;

import static cn.managame.demo.protocol.client.ClientProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandIntegrationTest {
    @cn.managame.runtime.annotation.Handler(routeType = cn.managame.demo.server.support.PlayerRoute.class)
    public static final class EchoHandler {
        @cn.managame.runtime.annotation.HandlerMethod
        public void echo(String request, cn.managame.demo.server.support.PlayerId player) {
            GameMessages.send(request + "!");
        }
    }

    @Test void runtimeUsesTheSuppliedCommandsAndHandlersWithoutTheGlobalCatalog() throws Exception {
        var echo = new cn.managame.demo.protocol.CommandBinding<>(3001, String.class, String.class);
        try (var runtime = new ServerRuntime(20, List.of(echo), new EchoHandler())) {
            assertNull(runtime.command(SPEND_GOLD));
            assertSame(echo, runtime.command(3001));
            var connection = new RecordingConnection();
            runtime.submit(echo.id(), "hello", connection, 1, CommandMetadata.player(7, 1));
            Sent sent = connection.next();
            assertEquals(3001, sent.commandId());
            assertEquals("hello!", sent.message());
        }
    }
    @Test void sharedBindingSuppliesCommandLookupAndRuntimeProtocolRelationships() {
        try (var runtime = new ServerRuntime(20, ClientCommands.COMMANDS, new WalletHandler(new cn.managame.demo.server.gameplay.Wallets()))) {
            assertSame(ClientCommands.SPEND, runtime.command(SPEND_GOLD));
            assertEquals(SpendGoldReq.class, runtime.engine().protocols().require(ProtocolType.REQUEST, SPEND_GOLD).messageType());
            assertEquals(SpendGoldRes.class, ClientCommands.SPEND.responseType());
            assertTrue(runtime.engine().protocols().find(SpendGoldRes.class).isEmpty());
        }
    }

    @Test void handlerUsesTheActualTransportConnectionFromContext() throws Exception {
        try (var runtime = new ServerRuntime(20, ClientCommands.COMMANDS, new WalletHandler(new cn.managame.demo.server.gameplay.Wallets()))) {
            var connection = new RecordingConnection();
            runtime.submit(ClientCommands.SPEND.id(), new SpendGoldReq(7, 10, 123), connection, 41, CommandMetadata.player(7, 123));
            Sent sent = connection.next();
            assertSame(connection, sent.connection());
            assertSame(connection, sent.context().connection());
            assertEquals(7, sent.context().routeKey());
            assertEquals(41, sent.requestId());
            assertEquals(41, sent.context().requestId());
            assertEquals(41, sent.context().invocation().requestId());
            assertEquals(SPEND_GOLD, sent.commandId());
            assertEquals(new SpendGoldRes(7, 90, 123), sent.message());
        }
    }

    @Test void overlappingRequestsOnOneConnectionKeepSeparateCorrelationAndPlayerIdentity() throws Exception {
        try (var runtime = new ServerRuntime(20, ClientCommands.COMMANDS, new WalletHandler(new cn.managame.demo.server.gameplay.Wallets()))) {
            var connection = new RecordingConnection();
            for (int i = 1; i <= 30; i++)
                runtime.submit(ClientCommands.SPEND.id(), new SpendGoldReq(i, 1, 1000 + i), connection, i, CommandMetadata.player(i, 1000 + i));
            var seen = ConcurrentHashMap.<Integer>newKeySet();
            for (int i = 0; i < 30; i++) {
                Sent sent = connection.next();
                assertTrue(seen.add(sent.requestId()));
                assertEquals(sent.requestId(), sent.context().requestId());
                assertSame(connection, sent.context().connection());
                assertEquals(new SpendGoldRes(sent.requestId(), 99, 1000 + sent.requestId()), sent.message());
            }
        }
    }

    @Test void businessFailureIsSentOnTheContextConnectionWithItsArguments() throws Exception {
        try (var runtime = new ServerRuntime(20, ClientCommands.COMMANDS, new WalletHandler(new cn.managame.demo.server.gameplay.Wallets()))) {
            var connection = new RecordingConnection();
            runtime.submit(ClientCommands.SPEND.id(), new SpendGoldReq(7, 101, 1), connection, 42, CommandMetadata.player(7, 1));
            Sent sent = connection.next();
            assertInstanceOf(ErrorRes.class, sent.message());
            assertSame(connection, sent.context().connection());
            assertEquals(42, sent.requestId());
            assertEquals(NOT_ENOUGH_GOLD, sent.code());
            assertEquals(List.of("101", "100"), ((ErrorRes) sent.message()).args());
        }
    }

    @Test void stopAdmissionSendsFrameworkErrorBeforeContextExists() throws Exception {
        try (var runtime = new ServerRuntime(20, ClientCommands.COMMANDS, new WalletHandler(new cn.managame.demo.server.gameplay.Wallets()))) {
            runtime.stopAdmission();
            var connection = new RecordingConnection();
            runtime.submit(ClientCommands.SPEND.id(), new SpendGoldReq(7, 1, 1), connection, 43, CommandMetadata.player(7, 1));
            Sent sent = connection.next();
            assertSame(connection, sent.connection());
            assertNull(sent.context());
            assertEquals(43, sent.requestId());
            assertEquals(RpcError.UNAVAILABLE.code(), sent.code());
        }
    }

    @Test void runtimeAdmissionFailureRepliesOnceThroughTheSharedFailureObserver() throws Exception {
        try (var runtime = new ServerRuntime(20, ClientCommands.COMMANDS, new WalletHandler(new cn.managame.demo.server.gameplay.Wallets()))) {
            runtime.engine().close();
            var connection = new RecordingConnection();
            runtime.submit(ClientCommands.SPEND.id(), new SpendGoldReq(7, 1, 1), connection, 44, CommandMetadata.player(7, 1));
            Sent sent = connection.next();
            assertEquals(44, sent.requestId());
            assertEquals(SPEND_GOLD, sent.commandId());
            assertEquals(RpcError.UNAVAILABLE.code(), sent.code());
            assertTrue(connection.messages.isEmpty());
        }
    }

    @Test void commandLookupFailureRetainsCorrelationBeforeARouteExists() throws Exception {
        try (var runtime = new ServerRuntime(20, ClientCommands.COMMANDS, new WalletHandler(new cn.managame.demo.server.gameplay.Wallets()))) {
            var connection = new RecordingConnection();
            runtime.engine().command(new cn.managame.runtime.context.CommandHandlerInvocation("unknown", connection, 45,
                    CommandMetadata.player(7, 1).with(CommandMetadata.COMMAND_ID, 9999)));
            fail("Expected command lookup failure");
        } catch (cn.managame.runtime.diagnostics.HandlerException expected) {
            assertEquals(cn.managame.runtime.diagnostics.HandlerException.CommandStage.RESOLUTION, expected.commandStage());
            var connection = (RecordingConnection) expected.invocation().connection();
            Sent sent = connection.next();
            assertEquals(45, sent.requestId());
            assertEquals(9999, sent.commandId());
            assertTrue(connection.messages.isEmpty());
        }
    }

    private record Sent(Connection connection, int requestId, int commandId, int code, Object message, CommandContext context) {}

    private static final class RecordingConnection implements Connection {
        private final ConcurrentHashMap<AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>();
        private final BlockingQueue<Sent> messages = new LinkedBlockingQueue<>();

        RecordingConnection() {
            GameMessages.bind(this, new GameMessages.Sender() {
                @Override public boolean send(Connection connection, int requestId, int commandId, Object message) {
                    return record(connection, requestId, commandId, 0, message);
                }
                @Override public boolean sendError(Connection connection, int requestId, int commandId, int code, String... args) {
                    return record(connection, requestId, commandId, code, new ErrorRes(List.of(args)));
                }
                private boolean record(Connection connection, int requestId, int commandId, int code, Object message) {
                    var context = HandlerContexts.currentOrNull();
                    messages.add(new Sent(connection, requestId, commandId, code, message,
                            context instanceof CommandContext command ? command : null));
                    return true;
                }
            });
        }
        Sent next() throws Exception {
            Sent sent = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(sent, "No response sent");
            return sent;
        }
        public String id() { return "test"; }
        public ConnectionType type() { return ConnectionType.TCP; }
        public boolean isActive() { return true; }
        public boolean isWritable() { return true; }
        public boolean write(Object message) { throw new UnsupportedOperationException("Test sender records decoded messages"); }
        public void close() {}
        public SocketAddress remoteAddress() { return null; }
        public <T> T get(AttributeKey<T> key) { return key.cast(attributes.get(key)); }
        public <T> void set(AttributeKey<T> key, T value) { attributes.put(key, value); }
        public <T> T remove(AttributeKey<T> key) { return key.cast(attributes.remove(key)); }
        public <T> boolean compareAndSet(AttributeKey<T> key, T expected, T update) {
            return expected == null ? attributes.putIfAbsent(key, update) == null : attributes.replace(key, expected, update);
        }
    }
}
