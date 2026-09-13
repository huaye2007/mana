package cn.managame.demo.server.rpc;

import cn.managame.demo.server.DemoServer;
import cn.managame.demo.serialization.MessageSerializer;

import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcOptions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static cn.managame.demo.protocol.rpc.RpcProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class InternalRpcTest {
    private DemoServer server;
    private InternalRpcClient client;

    @BeforeEach void start() throws Exception {
        server = new DemoServer(0);
        server.start();
        client = new InternalRpcClient();
        client.connect(server.rpcAddress());
    }

    @AfterEach void stop() {
        try { if (client != null) client.close(); }
        finally { if (server != null) server.close(); }
    }

    @Test void realTcpCallsPreservePlayerStateAndRuntimeMetadata() throws Exception {
        assertEquals(new GrantGoldRes(10001, 130, 9001), await(client.grant(10001, 30, 9001)));
        assertEquals(new GrantGoldRes(10001, 180, 9002), await(client.grant(10001, 50, 9002)));
        assertEquals(new GrantGoldRes(10002, 110, 9003), await(client.grant(10002, 10, 9003)));
    }

    @Test void businessErrorsPreserveBalanceAndCarryArguments() throws Exception {
        await(client.grant(10001, 80, 1));
        var error = failure(client.grant(10001, Integer.MAX_VALUE, 2));
        assertEquals(INVALID_GRANT, error.error().code());
        assertEquals(List.of(Integer.toString(Integer.MAX_VALUE), "180"), error.errorArgs());
        assertEquals(INVALID_GRANT, failure(client.grant(10001, 0, 3)).error().code());
        assertEquals(INVALID_GRANT, failure(client.grant(10001, -10, 4)).error().code());
        assertEquals(181, await(client.grant(10001, 1, 5)).gold());
    }

    @Test void queuedRequestsExecuteInOrderOnEachPlayerRoute() throws Exception {
        var first = new ArrayList<CompletableFuture<GrantGoldRes>>();
        var second = new ArrayList<CompletableFuture<GrantGoldRes>>();
        for (int i = 0; i < 50; i++) {
            first.add(client.grant(1, 1, i));
            second.add(client.grant(2, 2, i));
        }
        for (int i = 0; i < 50; i++) {
            assertEquals(new GrantGoldRes(1, 101 + i, i), await(first.get(i)));
            assertEquals(new GrantGoldRes(2, 102 + 2 * i, i), await(second.get(i)));
        }
    }

    @Test void concurrentCallersDoNotLoseWalletUpdates() throws Exception {
        var calls = new ArrayList<Future<GrantGoldRes>>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 80; i++) {
                final int trace = i;
                calls.add(executor.submit(() -> await(client.grant(7, 1, trace))));
            }
            var balances = new ArrayList<Integer>();
            for (var call : calls) balances.add(call.get(5, TimeUnit.SECONDS).gold());
            assertEquals(IntStream.rangeClosed(101, 180).boxed().toList(), balances.stream().sorted().toList());
        }
    }

    @Test void malformedPayloadUnknownCommandAndInvalidIdentityReplyPromptly() throws Exception {
        assertEquals(RpcError.PROTOCOL_ERROR.code(), rawFailure(GRANT_GOLD, new byte[3], RpcOptions.route(1)).error().code());
        assertEquals(RpcError.PROTOCOL_ERROR.code(), rawFailure(GRANT_GOLD, new byte[5], RpcOptions.route(1)).error().code());
        assertEquals(RpcError.NO_HANDLER, rawFailure(9999, new byte[4], RpcOptions.route(1)).error());
        assertEquals(RpcError.PROTOCOL_ERROR.code(), failure(client.grant(0, 1, 0)).error().code());
        assertEquals(RpcError.PROTOCOL_ERROR.code(), failure(client.grant(-1, 1, 0)).error().code());
        assertEquals(RpcError.PROTOCOL_ERROR.code(), rawFailure(GRANT_GOLD,
                MessageSerializer.serialize(new GrantGoldReq(1)), RpcOptions.builder().routeKey(1).putInt(TRACE_ID, 42).build()).error().code());
        assertEquals(101, await(client.grant(1, 1, 0)).gold());
    }

    @Test void missingTraceMetadataDefaultsToZero() throws Exception {
        ByteBuf body = MessageSerializer.serializeBuffer(new GrantGoldReq(1));
        try {
            assertEquals(new GrantGoldRes(1, 101, 0), await(client.call(GRANT_GOLD, body, RpcOptions.route(1))));
        } finally { body.release(); }
    }

    @Test void closedClientCompletesCallsWithFailure() {
        client.close();
        assertTrue(client.grant(1, 1, 0).handle((value, failure) -> failure != null).join());
    }

    private InternalRpcClient.CallFailure rawFailure(int command, byte[] payload, RpcOptions options) {
        ByteBuf body = Unpooled.wrappedBuffer(payload);
        try { return failure(client.call(command, body, options)); }
        finally { body.release(); }
    }

    private static InternalRpcClient.CallFailure failure(CompletableFuture<GrantGoldRes> result) {
        var error = assertThrows(ExecutionException.class, () -> await(result));
        return assertInstanceOf(InternalRpcClient.CallFailure.class, error.getCause());
    }

    private static GrantGoldRes await(CompletableFuture<GrantGoldRes> result) throws Exception {
        return result.get(5, TimeUnit.SECONDS);
    }
}
