package cn.managame.gateway.codec;

/**
 * 外网帧 {@code code} 字段取值。0~99 与 game-dev 的 {@code GameErrorCode} 对齐
 * （客户端一套错误码同时覆盖直连与走网关两种拓扑），100 起为网关自有错误段。
 */
public final class GatewayErrorCode {

    /** 成功。 */
    public static final int OK = 0;

    /** 服务器内部错误。 */
    public static final int INTERNAL_ERROR = 1;

    /** 请求非法：帧解析失败或参数校验不过。 */
    public static final int BAD_REQUEST = 3;

    /** 未登陆：登录命令之外的请求要求会话先通过登录校验。 */
    public static final int NOT_LOGGED_IN = 4;

    /** 服务器繁忙。 */
    public static final int SERVER_BUSY = 5;

    /** 踢下线原因：同账号在别处登陆（顶号）。 */
    public static final int DUPLICATE_LOGIN = 6;

    // ---- 网关自有错误段（100+），游戏服永远不会产生 ----

    /** 无可用后端：目标服务在注册中心无实例或 RPC 连接全断。 */
    public static final int NO_BACKEND = 100;

    /** 会话请求频率超限（RateLimitFilter）。 */
    public static final int RATE_LIMITED = 101;

    /** 同 IP 连接数/包速率超限（DdosFilter）。 */
    public static final int CONNECTION_LIMITED = 102;

    /** 网关转发失败：RPC 超时、连接断开等。 */
    public static final int GATEWAY_ERROR = 103;

    private GatewayErrorCode() {
    }
}
