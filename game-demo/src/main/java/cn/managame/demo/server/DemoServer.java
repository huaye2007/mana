package cn.managame.demo.server;

import cn.managame.demo.protocol.CommandBinding;
import cn.managame.demo.protocol.client.ClientCommands;
import cn.managame.demo.server.gameplay.WalletHandler;
import cn.managame.demo.server.gameplay.WalletRpcHandler;
import cn.managame.demo.server.gameplay.Wallets;
import cn.managame.demo.protocol.rpc.RpcCommands;
import cn.managame.demo.server.network.GameTcpServer;
import cn.managame.demo.server.rpc.GameRpcServer;
import cn.managame.demo.server.runtime.ServerRuntime;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;

/** Application composition and lifecycle. Business operations live in handlers and callers. */
public final class DemoServer implements AutoCloseable {
    public static final int NODE_ID = 20;
    private final ServerRuntime runtime;
    private final GameRpcServer rpc;
    private final GameTcpServer gameTcpServer;

    public DemoServer(int rpcPort) { this(NODE_ID, 0, rpcPort); }
    public DemoServer(int nodeId, int clientPort, int rpcPort) {
        this(nodeId, clientPort, rpcPort, createRuntime(nodeId));
    }

    private static ServerRuntime createRuntime(int nodeId) {
        var wallets = new Wallets();
        return new ServerRuntime(nodeId, java.util.List.of(ClientCommands.SPEND, ClientCommands.WALLET, RpcCommands.GRANT),
                new WalletHandler(wallets), new WalletRpcHandler(wallets));
    }

    DemoServer(int nodeId, int clientPort, int rpcPort, ServerRuntime runtime) {
        this(nodeId, clientPort, rpcPort, runtime, ServerIngress.TCP, ServerIngress.RPC);
    }

    DemoServer(int nodeId, int clientPort, int rpcPort, ServerRuntime runtime,
               Collection<CommandBinding<?, ?>> tcpRoutes, Collection<CommandBinding<?, ?>> rpcRoutes) {
        this.runtime = runtime;
        GameRpcServer createdRpc = null;
        try {
            createdRpc = new GameRpcServer(nodeId, rpcPort, runtime, rpcRoutes);
            rpc = createdRpc;
            gameTcpServer = new GameTcpServer(clientPort, runtime, tcpRoutes);
        } catch (RuntimeException | Error failure) {
            try { runtime.close(); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            if (createdRpc != null) {
                try { createdRpc.close(); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    public void start() {
        try { rpc.start(); gameTcpServer.start(); }
        catch (RuntimeException | Error failure) {
            try { close(); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public InetSocketAddress rpcAddress() { return rpc.address(); }
    public InetSocketAddress clientAddress() { return gameTcpServer.address(); }
    public int nodeId() { return rpc.nodeId(); }
    public GameRpcServer rpc() { return rpc; }

    /** On a drain timeout, retain reply transports; after work finishes the caller can retry close. */
    public void close(Duration timeout) {
        runtime.stopAdmission();
        rpc.stopCalls();
        runtime.close(timeout);
        try { rpc.close(); }
        finally { gameTcpServer.close(); }
    }
    @Override public void close() { close(Duration.ofSeconds(30)); }
}
