package cn.managame.gateway.rpc;

import cn.managame.common.context.MetadataKeys;
import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketConstant;
import cn.managame.gateway.router.BackendDirectory;
import cn.managame.gateway.router.BackendServiceResolver;
import cn.managame.gateway.session.GatewaySession;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.rpc.RpcRequest;
import cn.managame.rpc.protocol.Metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PacketForwarder {
    private final GatewayRpcClient rpcClient;
    private final BackendDirectory backendDirectory;
    private final BackendServiceResolver serviceResolver;
    private final int loginCommand;

    public PacketForwarder(GatewayRpcClient rpcClient, BackendDirectory backendDirectory,
                           BackendServiceResolver serviceResolver, int loginCommand) {
        this.rpcClient = Objects.requireNonNull(rpcClient, "rpcClient");
        this.backendDirectory = Objects.requireNonNull(backendDirectory, "backendDirectory");
        this.serviceResolver = Objects.requireNonNull(serviceResolver, "serviceResolver");
        if (loginCommand <= 0) throw new IllegalArgumentException("loginCommand must be positive");
        this.loginCommand = loginCommand;
    }

    public void forward(GatewaySession session, GatewayPacket packet) {
        String serviceName = serviceResolver.resolve(session, packet);
        ServiceInstance backend = backendDirectory.resolve(serviceName, session);
        if (backend == null) {
            reject(session, packet, GatewayErrorCode.NO_BACKEND);
            return;
        }
        List<Metadata> metadata = new ArrayList<>(3);
        metadata.add(Metadata.ofLong(MetadataKeys.GW_SEQ, packet.getSeq()));
        if (packet.getFlags() != 0) metadata.add(Metadata.ofLong(MetadataKeys.GW_FLAGS, packet.getFlags() & 0xffL));
        if (packet.getCommand() == loginCommand) metadata.add(Metadata.ofString(MetadataKeys.GW_CLIENT_IP, session.getClientIp()));
        byte busType = session.getRoleId() > 0 ? GatewayRpcProtocol.BUS_TYPE_ROLE : GatewayRpcProtocol.BUS_TYPE_SESSION;
        long busId = session.getRoleId() > 0 ? session.getRoleId() : session.getSessionId();
        RpcRequest request = RpcRequest.oneway(packet.getCommand())
                .routeKey(session.routeKey())
                .busType(busType)
                .busId(busId)
                .serialType(GatewayPacketConstant.BODY_SERIAL_TYPE)
                .body(packet.getBody())
                .metadata(metadata.toArray(Metadata[]::new));
        try {
            rpcClient.forward(serviceName, backend.getKey(), request);
        } catch (RuntimeException error) {
            session.setBackendServiceId(serviceName, null);
            reject(session, packet, GatewayErrorCode.SERVER_BUSY);
        }
    }

    private static void reject(GatewaySession session, GatewayPacket request, int code) {
        session.writeMsg(GatewayPacket.of(request.getCommand(), request.getSeq(), code, GatewayPacketConstant.EMPTY_BODY));
    }
}
