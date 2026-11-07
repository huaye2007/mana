package cn.managame.example.rpc;

import static cn.managame.FrameworkErrorCodes.RPC_PROTOCOL_ERROR;
import static cn.managame.FrameworkErrorCodes.RPC_UNAVAILABLE;

import cn.managame.FrameworkErrorCodes;
import cn.managame.MetadataKey;
import cn.managame.example.Protocols;
import cn.managame.example.proto.Rpc;
import cn.managame.rpc.RpcNode;
import cn.managame.rpc.RpcPeer;
import cn.managame.rpc.RpcRequest;
import cn.managame.rpc.RpcResponse;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeException;
import cn.managame.runtime.RequestContext;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.System.Logger.Level;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 业务调用别的节点的入口，只需要给出节点 id、请求消息和收到应答消息后做什么：
 *
 * <pre>{@code
 * rpc.call(NodeConstant.WORLD, join, (World.JoinWorldRes res) -> request.reply(res));
 * }</pre>
 *
 * 回调的参数类型就是应答消息的类型，要写在 lambda 上；写错时回调抛 ClassCastException，按业务 bug 处理。对端处理器返回
 * {@code HandleResult.ok(null)} 时回调收到 null。
 *
 * <p>回调回到发起调用的任务的路由上执行，所以可以直接改这条路由的数据。失败不进回调，这里统一处理：对端返回的错误码和参数，
 * 以及超时、连不上、对端繁忙等 RPC 自己的错误码，都当作当前请求的失败回给请求方；当前任务不是请求（定时器、事件）时，没有
 * 人可回，只记日志。
 */
public final class GameRpc {
    /** 成功应答的 metadata 里放消息的协议号，调用方按它解析 body；失败应答的 body 是 FailureArgs。 */
    static final MetadataKey<Integer> RESPONSE_COMMAND = MetadataKey.intKey(1);
    private static final System.Logger LOG = System.getLogger(GameRpc.class.getName());
    private final GameRuntime runtime;
    private final Protocols protocols;
    /** 由 RpcConfig 在节点启动后设置：节点依赖 Dispatcher，Dispatcher 依赖业务 bean，业务 bean 依赖这里。 */
    private volatile RpcNode node;

    public GameRpc(GameRuntime runtime, Protocols protocols) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.protocols = Objects.requireNonNull(protocols, "protocols");
    }

    void node(RpcNode node) { this.node = node; }

    /**
     * 把 request 发给节点 peerId，成功时在当前任务的路由上用应答消息执行 onResponse。必须在游戏任务里调用，否则抛
     * IllegalStateException，因为应答要回到这个任务的路由上。当前任务的路由键、业务身份和 metadata 随请求带给对端。
     */
    public <R extends Message> void call(int peerId, Message request, Consumer<R> onResponse) {
        Objects.requireNonNull(onResponse, "onResponse");
        Context context = Context.current();
        RpcNode current = node;
        RpcPeer peer = current == null ? null : current.peer(peerId);
        if (peer == null) {
            fail(context, RPC_UNAVAILABLE);
            return;
        }
        RpcRequest sending = RpcRequest.of(protocols.commandOf(request), context.routeKey(), context.businessType(),
                context.businessId(), context.metadata(), body(request));
        peer.call(sending, (sent, response) -> answered(context, response, onResponse));
    }

    /** 在 EventLoop 或时间轮上执行，response 在这里解析完就释放了，所以解析好再回到路由上。 */
    private <R extends Message> void answered(Context context, RpcResponse response, Consumer<R> onResponse) {
        if (!response.isSuccess()) {
            fail(context, response.code(), args(response.body()));
            return;
        }
        R message;
        try {
            message = decode(response);
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            LOG.log(Level.ERROR, "解析不了 RPC 应答", e);
            fail(context, RPC_PROTOCOL_ERROR);
            return;
        }
        try {
            runtime.execute(context, task -> onResponse.accept(message));
        } catch (GameRuntimeException refused) {
            // 回调是外部入口，路由已满或已停服时会被拒绝；请求已经以这个错误码应答了
            LOG.log(Level.WARNING, "RPC 应答回不到路由上：" + FrameworkErrorCodes.describe(refused.errorCode()));
        }
    }

    @SuppressWarnings("unchecked")
    private <R extends Message> R decode(RpcResponse response) throws InvalidProtocolBufferException {
        Integer command = response.metadata().get(RESPONSE_COMMAND);
        return command == null ? null : (R) protocols.parse(command, response.body().nioBuffer());
    }

    private static String[] args(ByteBuf body) {
        if (!body.isReadable()) return new String[0];
        try {
            return Rpc.FailureArgs.parseFrom(body.nioBuffer()).getArgsList().toArray(String[]::new);
        } catch (InvalidProtocolBufferException e) {
            LOG.log(Level.WARNING, "解析不了失败应答的参数", e);
            return new String[0];
        }
    }

    private static void fail(Context context, int code, String... args) {
        if (context instanceof RequestContext request) request.fail(code, args);
        else LOG.log(Level.WARNING, () -> "RPC 调用失败：" + FrameworkErrorCodes.describe(code) + " " + Arrays.toString(args));
    }

    static ByteBuf body(Message message) { return Unpooled.wrappedBuffer(message.toByteArray()); }
}
