package cn.managame.rpc;

import cn.managame.common.context.Metadata;
import cn.managame.common.context.MetadataKeys;

/**
 * RPC 响应：服务端用静态工厂构造回包；客户端侧 body 已按 responseType 反序列化。code==0 表示成功。
 * metadata 为响应携带的业务 metadata（无则 null），框架原样透传。
 *
 * <p><b>body 语义（出/入站不同，务必注意）：</b>
 * <ul>
 *   <li><b>出站</b>（服务端构造、待编码）：{@code body} 可为 {@code null}、{@code byte[]}（直写）
 *       或 POJO（由 {@code serialType} 对应的 serializer 序列化）。工厂方法 {@link #of} 走此路径。</li>
 *   <li><b>入站</b>（解码还原、交付业务/回调）：{@code body} 恒为 {@code byte[]}（空 body 为长度 0 的数组），
 *       由传输层解码器保证。需要按业务类型还原时用
 *       {@link #bodyAsBytes()} 取原始字节，再交由 serializer 反序列化。</li>
 * </ul>
 * 因此消费入站响应时<b>不要</b>直接 {@code (byte[]) body()} 强转——用 {@link #bodyAsBytes()}，
 * 后者对非 {@code byte[]} body 给出明确错误，避免 {@code ClassCastException} 在 IO/timer 线程被吞。
 */
public record RpcResponse(long requestId, int code, byte serialType, Object body, Metadata[] metadata) {

    /** 成功响应：body 走业务序列化（serialType 通常取自请求） */
    public static RpcResponse of(long requestId, byte serialType, Object body) {
        return new RpcResponse(requestId, 0, serialType, body, null);
    }

    /** 成功响应 + 业务 metadata */
    public static RpcResponse of(long requestId, byte serialType, Object body, Metadata[] metadata) {
        return new RpcResponse(requestId, 0, serialType, body, metadata);
    }

    /**
     * 失败响应：code 非 0，错误描述走 metadata（{@link cn.managame.common.context.MetadataKeys#RPC_ERROR_MESSAGE}），
     * 不占 body、不依赖业务序列化器，客户端拼进 GameRpcException 消息。
     */
    public static RpcResponse error(long requestId, int code, String message) {
        Metadata[] metadata = message == null ? null
                : new Metadata[]{Metadata.ofString(MetadataKeys.RPC_ERROR_MESSAGE, message)};
        return new RpcResponse(requestId, code, (byte) 0, null, metadata);
    }

    public boolean isSuccess() {
        return code == 0;
    }

    /** 取 string 类型响应 metadata 的值，不存在返回 null */
    public String metaString(short key) {
        return Metadata.findString(metadata, key);
    }

    /**
     * 以 {@code byte[]} 形式取响应 body，用于反序列化或原始字节透传。body 为 {@code null} 返回 {@code null}，
     * body 非 {@code byte[]}（如误把 POJO 当作入站响应）抛 {@link GameRpcException}，给出明确错误信息，
     * 避免裸 {@code (byte[]) body()} 强转产生的 {@code ClassCastException} 在 IO/timer 线程被吞。
     */
    public byte[] bodyAsBytes() {
        Object b = body;
        if (b == null) {
            return null;
        }
        if (b instanceof byte[] bytes) {
            return bytes;
        }
        throw new GameRpcException("rpc response body is not byte[]: " + b.getClass().getName()
                + " (inbound response must be produced by RpcCodec decoder)");
    }
}
