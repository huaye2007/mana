package cn.managame.gateway.rpc;

/**
 * 网关 ↔ 后端游戏服的转发协议约定（承载在 game-rpc 的 oneway 帧上，复用其连接池与协议）。
 *
 * <p>外网 {@code GamePacket} 的 command/body 直接映射到 RPC 帧的 command/body；<b>会话定位走 RPC 帧的
 * {@code busType} + {@code busId}</b>（不占 metadata），其余（seq、code、clientIp）走 metadata。
 * metadata key 见 {@link cn.managame.common.context.MetadataKeys} 的 {@code GW_*}（与 rpc 保留 key 同在 1~99 框架段）。</p>
 *
 * <p><b>会话定位</b>（{@code busType} + {@code busId}，双向一致）：会话未绑定角色时
 * {@code busType=}{@link #BUS_TYPE_SESSION}、{@code busId=sessionId}；一旦绑定角色则
 * {@code busType=}{@link #BUS_TYPE_ROLE}、{@code busId=roleId}（此后不再带 sessionId）。</p>
 *
 * <p><b>上行</b>（网关→后端，oneway）：command=客户端命令，body=原始包体字节，routeKey=会话路由键，
 * busType/busId=会话定位；metadata 带 {@link cn.managame.common.context.MetadataKeys#GW_SEQ}（原样回echo），
 * 登录命令额外带 {@link cn.managame.common.context.MetadataKeys#GW_CLIENT_IP}。</p>
 *
 * <p><b>下行</b>（后端→网关，oneway，走同一条连接回来）：command=回包/推送命令，body=回包体，
 * busType/busId=目标会话定位；metadata 带 {@link cn.managame.common.context.MetadataKeys#GW_SEQ}（回请求的 seq，
 * 主动推送填 0）、{@link cn.managame.common.context.MetadataKeys#GW_CODE}（业务错误码）。后端首次绑定角色时在
 * 登录响应用 {@code routeKey} 回带 roleId，触发网关侧 roleId→会话 绑定与顶号。</p>
 */
public final class GatewayRpcProtocol {

    /** 会话定位类型：{@code busId} 承载 sessionId（会话未绑定角色，也是 busType 默认值 0）。 */
    public static final byte BUS_TYPE_SESSION = 0;

    /** 会话定位类型：{@code busId} 承载 roleId（会话已绑定角色）。 */
    public static final byte BUS_TYPE_ROLE = 1;

    private GatewayRpcProtocol() {
    }
}
