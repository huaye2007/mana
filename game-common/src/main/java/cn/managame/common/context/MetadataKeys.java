package cn.managame.common.context;

/**
 * 框架各组件定义的 metadata key 的唯一注册表。metadata 的 key 为 {@code short}，集中在此分配以避免跨组件撞号。
 * <p>
 * 号段约定：<b>1~99 保留给框架各组件</b>（game-rpc 握手/响应、game-gateway 转发等）；<b>业务自定义 key 从 {@link #BUSINESS_MIN}(100) 起</b>。
 * rpc 帧 metadata、网关转发、任务上下文 {@link Metadata} 共用同一号段。新增 key 时在此登记，勿在各模块private 定义。
 */
public final class MetadataKeys {

    private MetadataKeys() {
    }

    /** 业务自定义 metadata key 的起始值；小于此值（1~99）保留给框架底层。 */
    public static final short BUSINESS_MIN = 100;

    // ===== game-rpc 保留 key（1~4）=====

    /** rpc 握手：服务名（string）。 */
    public static final short RPC_SERVICE_NAME = 1;
    /** rpc 握手：服务实例 id（string）。 */
    public static final short RPC_SERVICE_ID = 2;
    /** rpc 握手：鉴权 token（string）。 */
    public static final short RPC_AUTH_TOKEN = 3;
    /** rpc 失败响应（code!=0）的错误描述（string）。 */
    public static final short RPC_ERROR_MESSAGE = 4;

    // ===== game-gateway 转发协议 key（5~7）=====

    /** 网关：客户端 seq（long）。上行原样带、下行回 echo；主动推送为 0。 */
    public static final short GW_SEQ = 5;
    /** 网关：业务错误码（long）。仅下行。 */
    public static final short GW_CODE = 6;
    /** 网关：客户端来源 IP（string）。仅上行登录命令。 */
    public static final short GW_CLIENT_IP = 7;
}
