package cn.managame.gateway.session;

import cn.managame.network.connection.IConnection;
import cn.managame.network.session.DefaultSession;

/**
 * 网关侧的客户端会话：连接之上叠加登录态、角色绑定和后端粘滞路由。
 *
 * <p>状态跃迁：连接建立（未认证）→ 登录包转发后端且 code=0（{@link #setAuthenticated}）
 * → 后端通过控制消息回填 roleId（{@link #setRoleId}，可选）。
 * 断线由 {@code GatewaySessionManager} 统一解绑。</p>
 */
public class GatewaySession extends DefaultSession {

    private final long sessionId;
    private final String clientIp;

    /** 登录校验是否通过；未通过的会话只放行登录命令（见 AuthFilter）。 */
    private volatile boolean authenticated;

    /** 后端回填的角色标识，0 表示未绑定；绑定后作为路由键、上行会话定位键与顶号判定依据。 */
    private volatile long roleId;

    /** 粘滞后端实例 ID：会话首包路由选定后固定，实例下线时才重选。 */
    private volatile String backendServiceId;

    public GatewaySession(IConnection connection, String clientIp) {
        super(connection);
        this.sessionId = connection.getConnectionId();
        this.clientIp = clientIp;
    }

    public long getSessionId() {
        return sessionId;
    }

    public String getClientIp() {
        return clientIp;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public long getRoleId() {
        return roleId;
    }

    public void setRoleId(long roleId) {
        this.roleId = roleId;
    }

    public String getBackendServiceId() {
        return backendServiceId;
    }

    public void setBackendServiceId(String backendServiceId) {
        this.backendServiceId = backendServiceId;
    }

    /** 转发路由键：已绑定角色按 roleId（同玩家恒定），否则按 sessionId 打散。 */
    public long routeKey() {
        long bound = roleId;
        return bound != 0 ? bound : sessionId;
    }
}
