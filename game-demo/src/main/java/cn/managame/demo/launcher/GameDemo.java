package cn.managame.demo.launcher;

import cn.managame.demo.client.DemoClient;
import cn.managame.demo.server.DemoServer;
import cn.managame.demo.protocol.client.ClientProtocol;
import cn.managame.demo.protocol.client.ClientCommands;
import cn.managame.demo.protocol.rpc.RpcCommands;
import static cn.managame.demo.protocol.rpc.RpcProtocol.*;
import cn.managame.demo.server.support.PlayerRoute;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.runtime.route.Route;
import java.util.concurrent.CompletableFuture;
import static cn.managame.demo.protocol.client.ClientProtocol.*;
import cn.managame.demo.protocol.GameCallException;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Direct client-to-game-server networking, plus a separate server-to-server RPC demonstration. */
public final class GameDemo {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            try (DemoServer server = new DemoServer(20, 0, 0);
                 DemoServer peer = new DemoServer(21, 0, 0)) {
                server.start();
                peer.start();
                System.out.println("Game server: clients=" + server.clientAddress() + ", internal RPC=" + server.rpcAddress());
                runClient(server.clientAddress());
                peer.rpc().connectPeer(server.nodeId(), server.rpcAddress());
                var wallet = grantOnPeer(peer, server.nodeId(), 20001, 10, 20260914L).get(5, TimeUnit.SECONDS);
                System.out.printf("Server 21 -> server 20 grant RPC: player=%d gold=%d trace=%d%n",
                        wallet.playerId(), wallet.gold(), wallet.traceId());
            }
            return;
        }
        switch (args[0]) {
            case "server" -> {
                if (args.length > 4) throw usage();
                int clientPort = args.length > 1 ? port(args[1]) : 7000;
                int rpcPort = args.length > 2 ? port(args[2]) : 7070;
                int nodeId = args.length > 3 ? Integer.parseInt(args[3]) : 20;
                try (DemoServer server = new DemoServer(nodeId, clientPort, rpcPort)) {
                    server.start();
                    System.out.println("Clients=" + server.clientAddress() + ", internal RPC=" + server.rpcAddress()
                            + "; press Enter to stop.");
                    System.in.read();
                }
            }
            case "client" -> {
                if (args.length > 2) throw usage();
                runClient(new InetSocketAddress("127.0.0.1", args.length == 2 ? port(args[1]) : 7000));
            }
            case "peer" -> {
                if (args.length > 3) throw usage();
                int rpcPort = args.length > 1 ? port(args[1]) : 7070;
                int targetId = args.length > 2 ? Integer.parseInt(args[2]) : 20;
                if (targetId == 21) throw new IllegalArgumentException("Demo peer uses node ID 21; target must differ");
                try (DemoServer peer = new DemoServer(21, 0, 0)) {
                    peer.start();
                    peer.rpc().connectPeer(targetId, new InetSocketAddress("127.0.0.1", rpcPort));
                    System.out.println("Server-to-server RPC: "
                            + grantOnPeer(peer, targetId, 20001, 10, 20260914L).get(5, TimeUnit.SECONDS));
                }
            }
            default -> throw usage();
        }
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage: [server [clientPort [rpcPort [nodeId]]]] | [client [clientPort]] | [peer [rpcPort [targetNodeId]]]");
    }

    private static int port(String value) {
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1..65535");
        return port;
    }

    private static void runClient(InetSocketAddress address) throws Exception {
        try (DemoClient client = new DemoClient()) {
            client.connect(address);
            System.out.println("Game client connected directly via game-network");
            for (int amount : new int[] {30, 50, 30}) {
                try {
                    var wallet = client.call(ClientCommands.SPEND, new SpendGoldReq(10001, amount, 20260913L)).get(5, TimeUnit.SECONDS);
                    System.out.printf("player=%d spent=%d gold=%d trace=%d%n",
                            wallet.playerId(), amount, wallet.gold(), wallet.traceId());
                } catch (ExecutionException failure) {
                    if (!(failure.getCause() instanceof GameCallException call)
                            || call.errorCode() != ClientProtocol.NOT_ENOUGH_GOLD) throw failure;
                    System.out.println("Spend rejected: " + call.getMessage());
                }
            }
        }
    }

    private static CompletableFuture<GrantGoldRes> grantOnPeer(DemoServer server, int peer, long player, int amount, long trace) {
        return server.rpc().call(peer, RpcCommands.GRANT, new GrantGoldReq(amount), new Route(PlayerRoute.class, player),
                RpcOptions.builder().routeKey(player).putLong(TRACE_ID, trace).build());
    }

    private GameDemo() {}
}
