package cn.managame.gateway.rpc;

import cn.managame.common.context.Metadata;
import cn.managame.common.context.MetadataKeys;
import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketConstant;
import cn.managame.gateway.router.BackendDirectory;
import cn.managame.gateway.router.BackendServiceResolver;
import cn.managame.gateway.session.GatewaySession;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.rpc.RpcRequest;

import java.util.Objects;

public final class PacketForwarder {
    @FunctionalInterface
    interface RequestSender {
        boolean forward(String serviceName, String serviceId, RpcRequest request);
    }

    private final RequestSender requestSender;
    private final BackendDirectory backendDirectory;
    private final BackendServiceResolver serviceResolver;
    private final int loginCommand;

    public PacketForwarder(GatewayRpcClient rpcClient, BackendDirectory backendDirectory,
                           BackendServiceResolver serviceResolver, int loginCommand) {
        this(Objects.requireNonNull(rpcClient, "rpcClient")::tryForward,
                backendDirectory, serviceResolver, loginCommand);
    }

    PacketForwarder(RequestSender requestSender, BackendDirectory backendDirectory,
                    BackendServiceResolver serviceResolver, int loginCommand) {
        this.requestSender = Objects.requireNonNull(requestSender, "requestSender");
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
        boolean hasFlags = packet.getFlags() != 0;
        boolean isLogin = packet.getCommand() == loginCommand;
        Metadata[] metadata = new Metadata[1 + (hasFlags ? 1 : 0) + (isLogin ? 1 : 0)];
        int metadataIndex = 0;
        metadata[metadataIndex++] = Metadata.ofLong(MetadataKeys.GW_SEQ, packet.getSeq());
        if (hasFlags) metadata[metadataIndex++] = Metadata.ofLong(MetadataKeys.GW_FLAGS, packet.getFlags() & 0xffL);
        if (isLogin) metadata[metadataIndex] = Metadata.ofString(MetadataKeys.GW_CLIENT_IP, session.getClientIp());
        byte busType = session.getRoleId() > 0 ? GatewayRpcProtocol.BUS_TYPE_ROLE : GatewayRpcProtocol.BUS_TYPE_SESSION;
        long busId = session.getRoleId() > 0 ? session.getRoleId() : session.getSessionId();
        RpcRequest request = RpcRequest.oneway(packet.getCommand())
                .routeKey(session.routeKey())
                .busType(busType)
                .busId(busId)
                .serialType(GatewayPacketConstant.BODY_SERIAL_TYPE)
                .body(packet.getBody())
                .metadata(metadata);
        try {
            if (!requestSender.forward(serviceName, backend.getKey(), request)) {
                session.setBackendServiceId(serviceName, null);
                reject(session, packet, GatewayErrorCode.SERVER_BUSY);
            }
        } catch (RuntimeException error) {
            session.setBackendServiceId(serviceName, null);
            reject(session, packet, GatewayErrorCode.SERVER_BUSY);
        }
    }

    private static void reject(GatewaySession session, GatewayPacket request, int code) {
        session.writeMsg(GatewayPacket.of(request.getCommand(), request.getSeq(), code, GatewayPacketConstant.EMPTY_BODY));
    }
}
