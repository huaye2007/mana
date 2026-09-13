package cn.managame.demo.client;

import static cn.managame.demo.DemoCalls.*;

import cn.managame.demo.server.DemoServer;
import cn.managame.demo.protocol.GamePacket;
import cn.managame.demo.protocol.client.ClientCommands;
import cn.managame.demo.protocol.rpc.RpcCommands;
import static cn.managame.demo.protocol.rpc.RpcProtocol.*;
import cn.managame.demo.server.support.PlayerRoute;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.runtime.route.Route;
import cn.managame.demo.serialization.MessageSerializer;

import java.nio.ByteBuffer;
import cn.managame.demo.protocol.GameCallException;

import cn.managame.rpc.protocol.RpcError;
import org.junit.jupiter.api.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static cn.managame.demo.protocol.client.ClientProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class GameDemoTest {
    private DemoServer server;
    private DemoClient client;

    @BeforeEach void start() throws Exception {
        server = new DemoServer(20, 0, 0);
        server.start();
        client = new DemoClient();
        client.connect(server.clientAddress());
    }

    @AfterEach void stop() {
        try { if (client != null) client.close(); }
        finally { if (server != null) server.close(); }
    }

    @Test void gameClientConnectsDirectlyAndReceivesResultsAndBusinessErrors() throws Exception {
        assertNotEquals(server.rpcAddress().getPort(), server.clientAddress().getPort());
        assertEquals(new SpendGoldRes(10001, 70, 9001), await(spend(client, 10001, 30, 9001)));
        assertEquals(new SpendGoldRes(10001, 20, 9002), await(spend(client, 10001, 50, 9002)));
        var error = failure(spend(client, 10001, 30, 9003));
        assertEquals(NOT_ENOUGH_GOLD, error.errorCode());
        assertEquals(List.of("30", "20"), error.errorArgs());
        assertEquals(new SpendGoldRes(10002, 90, 9004), await(spend(client, 10002, 10, 9004)));
    }

    @Test void pipelinedClientCommandsKeepPlayerFifoAndRequestCorrelation() throws Exception {
        var first = new ArrayList<CompletableFuture<SpendGoldRes>>();
        var second = new ArrayList<CompletableFuture<SpendGoldRes>>();
        for (int i = 0; i < 40; i++) {
            first.add(spend(client, 1, 1, i));
            second.add(spend(client, 2, 2, i));
        }
        for (int i = 0; i < 40; i++) {
            assertEquals(new SpendGoldRes(1, 99 - i, i), await(first.get(i)));
            assertEquals(new SpendGoldRes(2, 98 - 2 * i, i), await(second.get(i)));
        }
    }

    @Test void clientsAndInternalServerCallsShareOneOrderedWallet() throws Exception {
        try (DemoClient anotherClient = new DemoClient(); DemoServer peer = new DemoServer(21, 0, 0)) {
            anotherClient.connect(server.clientAddress());
            peer.start();
            peer.rpc().connectPeer(server.nodeId(), server.rpcAddress());
            var calls = new ArrayList<CompletableFuture<?>>();
            for (int i = 0; i < 20; i++) {
                calls.add(spend(client, 7, 1, i));
                calls.add(spend(anotherClient, 7, 1, i));
                calls.add(grantOnPeer(peer, server.nodeId(), 7, 1, i));
            }
            for (var call : calls) await(call);
            assertEquals(new GetWalletRes(7, 80), await(client.call(ClientCommands.WALLET, new GetWalletReq(7, 99))));
            assertEquals(new GrantGoldRes(7, 101, 42), await(anotherClientOnPeer(peer, 7)));
            // Same player ID on another game server has independent local state.
        }
    }

    private CompletableFuture<GrantGoldRes> anotherClientOnPeer(DemoServer peer, long playerId) throws Exception {
        // The existing physical RPC connection is bidirectional; no reverse connect is needed.
        return grantOnPeer(server, peer.nodeId(), playerId, 1, 42);
    }

    @Test void internalRpcCallbackPropagatesErrorsWithoutHanging() throws Exception {
        try (DemoServer peer = new DemoServer(21, 0, 0)) {
            peer.start();
            peer.rpc().connectPeer(server.nodeId(), server.rpcAddress());
            assertEquals(INVALID_GRANT,
                    failure(grantOnPeer(peer, server.nodeId(), 1, 0, 0)).errorCode());
            assertEquals(RpcError.UNAVAILABLE.code(), failure(grantOnPeer(peer, 999, 1, 1, 0)).errorCode());
            assertEquals(new GrantGoldRes(1, 101, 123), await(grantOnPeer(peer, server.nodeId(), 1, 1, 123)));
        }
    }

    @Test void invalidClientCommandsReturnErrorsAndDoNotChangeState() throws Exception {
        assertEquals(INVALID_REQUEST, failure(spend(client, 0, 1, 0)).errorCode());
        assertEquals(INVALID_REQUEST, failure(spend(client, 1, -1, 0)).errorCode());
        assertEquals(RpcError.NO_HANDLER.code(), failure(client.call(new cn.managame.demo.protocol.CommandBinding<>(9999, SpendGoldReq.class, SpendGoldRes.class), new SpendGoldReq(1, 10, 0))).errorCode());
        assertEquals(99, await(spend(client, 1, 1, 0)).gold());
    }

    @Test void tcpFramingHandlesFragmentationAndBackToBackMessages() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(server.clientAddress(), 5000);
            socket.setSoTimeout(5000);
            var output = new DataOutputStream(socket.getOutputStream());
            var input = new DataInputStream(socket.getInputStream());
            byte[] first = requestFrame(1, 10, 11);
            byte[] second = requestFrame(2, 20, 12);
            output.write(first, 0, 7);
            output.flush();
            output.write(first, 7, first.length - 7);
            output.write(second);
            output.flush();
            assertWallet(read(input), 1, new SpendGoldRes(1, 90, 11));
            assertWallet(read(input), 2, new SpendGoldRes(1, 70, 12));
        }
    }

    @Test void malformedClientFramesCloseOnlyThatConnection() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(server.clientAddress(), 5000);
            socket.setSoTimeout(5000);
            var output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(3);
            output.write(new byte[3]);
            output.flush();
            assertEquals(-1, socket.getInputStream().read());
        }
        assertEquals(99, await(spend(client, 1, 1, 0)).gold());
    }

    @Test void disconnectedClientCallsCompleteWithFailure() {
        client.close();
        assertThrows(ExecutionException.class, () -> await(spend(client, 1, 1, 0)));
    }

    private static byte[] requestFrame(int requestId, int amount, long trace) {
        byte[] body = MessageSerializer.serialize(new SpendGoldReq(1, amount, trace));
        return ByteBuffer.allocate(20 + body.length).putInt(16 + body.length)
                .putInt(SPEND_GOLD).putInt(requestId).putInt(0).putInt(GamePacket.REQUEST).put(body).array();
    }

    private static GamePacket read(DataInputStream input) throws Exception {
        int size = input.readInt();
        assertTrue(size >= GamePacket.HEADER_BYTES && size <= GamePacket.MAX_FRAME_BYTES - 4);
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        ByteBuffer frame = ByteBuffer.wrap(bytes);
        int command = frame.getInt(), requestId = frame.getInt(), code = frame.getInt(), flags = frame.getInt();
        byte[] body = new byte[frame.remaining()];
        frame.get(body);
        return new GamePacket(command, requestId, code, flags, body);
    }

    private static void assertWallet(GamePacket packet, int requestId, SpendGoldRes wallet) {
        assertEquals(SPEND_GOLD, packet.command());
        assertEquals(requestId, packet.requestId());
        assertEquals(0, packet.code());
        assertEquals(GamePacket.RESPONSE, packet.flags());
        assertEquals(wallet, MessageSerializer.deserialize(packet.body(), SpendGoldRes.class));
    }

    @Test void invalidBodyReturnsCorrelatedErrorWithoutBreakingFraming() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(server.clientAddress(), 5000);
            socket.setSoTimeout(5000);
            var output = new DataOutputStream(socket.getOutputStream());
            var input = new DataInputStream(socket.getInputStream());
            output.writeInt(16); output.writeInt(SPEND_GOLD); output.writeInt(77); output.writeInt(0); output.writeInt(0);
            output.flush();
            GamePacket failure = read(input);
            assertEquals(SPEND_GOLD, failure.command());
            assertEquals(77, failure.requestId());
            assertEquals(INVALID_REQUEST, failure.code());
            assertEquals(GamePacket.RESPONSE, failure.flags());
            assertEquals(List.of(), MessageSerializer.deserialize(failure.body(), ErrorRes.class).args());
            output.write(requestFrame(78, 1, 12)); output.flush();
            assertWallet(read(input), 78, new SpendGoldRes(1, 99, 12));
        }
    }

    @Test void responsePacketSentToServerClosesOnlyThatConnection() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(server.clientAddress(), 5000);
            socket.setSoTimeout(5000);
            var output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(16); output.writeInt(SPEND_GOLD); output.writeInt(1); output.writeInt(0); output.writeInt(GamePacket.RESPONSE);
            output.flush();
            assertEquals(-1, socket.getInputStream().read());
        }
        assertEquals(99, await(spend(client, 1, 1, 0)).gold());
    }

    @Test void differentCommandsShareTcpCorrelationAndRpcDispatch() throws Exception {
        var before = client.call(ClientCommands.WALLET, new GetWalletReq(7, 1));
        var spend = client.call(ClientCommands.SPEND, new SpendGoldReq(7, 12, 2));
        var after = client.call(ClientCommands.WALLET, new GetWalletReq(7, 3));
        assertEquals(new GetWalletRes(7, 100), before.get(5, TimeUnit.SECONDS));
        assertEquals(new SpendGoldRes(7, 88, 2), spend.get(5, TimeUnit.SECONDS));
        assertEquals(new GetWalletRes(7, 88), after.get(5, TimeUnit.SECONDS));
        try (var peer = new DemoServer(21, 0, 0)) {
            peer.start();
            peer.rpc().connectPeer(server.nodeId(), server.rpcAddress());
            assertEquals(new GrantGoldRes(7, 100, 0), peer.rpc().call(server.nodeId(), RpcCommands.GRANT,
                    new GrantGoldReq(12), new Route(PlayerRoute.class, 7), RpcOptions.route(7)).get(5, TimeUnit.SECONDS));
        }
    }

    @Test void commandWithWrongRegisteredBodyIsRejectedWithoutChangingWallet() throws Exception {
        var wrong = new cn.managame.demo.protocol.CommandBinding<>(SPEND_GOLD, GetWalletReq.class, SpendGoldRes.class);
        assertEquals(INVALID_REQUEST, failure(client.call(wrong, new GetWalletReq(7, 1))).errorCode());
        assertEquals(new GetWalletRes(7, 100),
                client.call(ClientCommands.WALLET, new GetWalletReq(7, 2)).get(5, TimeUnit.SECONDS));
    }

    private static GameCallException failure(CompletableFuture<?> result) {
        var error = assertThrows(ExecutionException.class, () -> await(result));
        return assertInstanceOf(GameCallException.class, error.getCause());
    }

    private static <T> T await(CompletableFuture<T> result) throws Exception {
        return result.get(6, TimeUnit.SECONDS);
    }
}
