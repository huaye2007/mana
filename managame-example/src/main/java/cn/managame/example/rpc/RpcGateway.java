package cn.managame.example.rpc;

import cn.managame.FrameworkErrorCodes;
import cn.managame.Metadata;
import cn.managame.example.Protocols;
import cn.managame.example.proto.Rpc;
import cn.managame.network.connection.Connection;
import cn.managame.rpc.RpcHandler;
import cn.managame.rpc.RpcPeer;
import cn.managame.rpc.RpcRequest;
import cn.managame.rpc.RpcResponse;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.dispatch.Dispatcher;
import cn.managame.runtime.dispatch.HandleResult;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;

/**
 * 别的节点发来的请求走的入口，和 GameGateway 对客户端一样：按协议号解析出消息，连同请求带来的业务身份和 metadata 交给
 * Dispatcher，处理器还是普通的 {@code @HandleMethod}；再把 HandleResult 写成 RpcResponse。成功时 body 是应答消息，
 * metadata 里是它的协议号；失败时是错误码，参数放在 body 的 FailureArgs 里。示例只用调用，通知不处理。
 */
final class RpcGateway implements RpcHandler {
    private final Dispatcher dispatcher;
    private final Protocols protocols;

    RpcGateway(Dispatcher dispatcher, Protocols protocols) {
        this.dispatcher = dispatcher;
        this.protocols = protocols;
    }

    @Override public void handleMessage(RpcPeer rpcPeer, Connection connection, Object msg) {
        if (!(msg instanceof RpcRequest request)) return;
        int requestId = request.requestId();
        GameContext source = new GameContext(0, request.routeKey(), request.businessType(), request.businessId(), request.metadata());
        dispatcher.dispatch(source, decode(request), result -> rpcPeer.reply(connection, response(requestId, result)));
    }

    /** 解析不了时抛异常，rpc 以 RPC_HANDLER_ERROR 应答并记日志。 */
    private Message decode(RpcRequest request) {
        try {
            return protocols.parse(request.command(), request.body().nioBuffer());
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("解析不了协议号 " + request.command() + " 的 RPC 请求", e);
        }
    }

    private RpcResponse response(int requestId, HandleResult result) {
        if (result.code() != FrameworkErrorCodes.SUCCESS)
            return RpcResponse.failure(requestId, result.code(), Metadata.empty(), args(result.args()));
        if (!(result.data() instanceof Message body)) return RpcResponse.of(requestId, Unpooled.EMPTY_BUFFER);
        Metadata command = Metadata.builder().put(GameRpc.RESPONSE_COMMAND, protocols.commandOf(body)).build();
        return RpcResponse.of(requestId, command, GameRpc.body(body));
    }

    private static ByteBuf args(String[] args) {
        if (args.length == 0) return Unpooled.EMPTY_BUFFER;
        return GameRpc.body(Rpc.FailureArgs.newBuilder().addAllArgs(List.of(args)).build());
    }
}
