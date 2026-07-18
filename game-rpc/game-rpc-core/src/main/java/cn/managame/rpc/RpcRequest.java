package cn.managame.rpc;

import cn.managame.common.context.Metadata;


public final class RpcRequest {

    /** command 内部段：[-100, -1] 保留给框架内部消息（心跳/握手），业务命令必须为正数 */
    public static final int INTERNAL_COMMAND_MIN = -100;
    public static final int INTERNAL_COMMAND_MAX = -1;

    private final int command;
    private final boolean oneway;
    private long requestId; // REQUEST 发送时由客户端分配；服务端用它构造响应做关联
    private long routeKey;
    private byte busType;
    private long busId;
    private byte serialType;
    private long timeoutMillis; // <=0 用客户端默认超时
    private Object body; // 出站为业务对象或 byte[]；入站恒为 byte[]，由接收方按 serialType 还原
    private Class<?> responseType; // null 或 byte[].class 表示原样返回 byte[]
    private Metadata[] metadata; // 业务 metadata，key 从 MetadataKeys.BUSINESS_MIN 起，框架原样透传

    private RpcRequest(int command, boolean oneway) {
        this.command = command;
        this.oneway = oneway;
    }


    public static RpcRequest of(int command) {
        return new RpcRequest(command, false);
    }

    /** oneway 消息：无 requestId、无响应。服务端主动推送也用它构造 */
    public static RpcRequest oneway(int command) {
        return new RpcRequest(command, true);
    }

    public static boolean isInternalCommand(int command) {
        return command >= INTERNAL_COMMAND_MIN && command <= INTERNAL_COMMAND_MAX;
    }

    /** 框架内部消息（心跳/握手）：oneway + 内部命令段，业务 handler 永远看不到 */
    public boolean isInternal() {
        return oneway && isInternalCommand(command);
    }

    /** 框架内部使用（客户端发送时分配 / 解码端还原），业务无需设置 */
    public RpcRequest requestId(long requestId) {
        this.requestId = requestId;
        return this;
    }

    public RpcRequest routeKey(long routeKey) {
        this.routeKey = routeKey;
        return this;
    }

    public RpcRequest busType(byte busType) {
        this.busType = busType;
        return this;
    }

    public RpcRequest busId(long busId) {
        this.busId = busId;
        return this;
    }

    public RpcRequest serialType(byte serialType) {
        this.serialType = serialType;
        return this;
    }

    public RpcRequest timeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        return this;
    }

    public RpcRequest body(Object body) {
        this.body = body;
        return this;
    }

    public RpcRequest responseType(Class<?> responseType) {
        this.responseType = responseType;
        return this;
    }

    /**
     * 业务 metadata，随帧透传给对端。业务自定义 key 从 {@link cn.managame.common.context.MetadataKeys#BUSINESS_MIN} 起，
     * 低段保留给框架。
     */
    public RpcRequest metadata(Metadata[] metadata) {
        this.metadata = metadata;
        return this;
    }


    public int getCommand() {
        return command;
    }

    public boolean isOneway() {
        return oneway;
    }

    public long getRequestId() {
        return requestId;
    }

    public long getRouteKey() {
        return routeKey;
    }

    public byte getBusType() {
        return busType;
    }

    public long getBusId() {
        return busId;
    }

    public byte getSerialType() {
        return serialType;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public Object getBody() {
        return body;
    }

    public Class<?> getResponseType() {
        return responseType;
    }

    public Metadata[] getMetadata() {
        return metadata;
    }

    /** 取 string 类型 metadata 的值，不存在返回 null */
    public String metaString(short key) {
        return Metadata.findString(metadata, key);
    }
}
