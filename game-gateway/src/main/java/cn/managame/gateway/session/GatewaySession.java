package cn.managame.gateway.session;

import cn.managame.network.connection.IConnection;
import cn.managame.network.session.DefaultSession;

import java.util.Objects;

public final class GatewaySession extends DefaultSession {
    private final long sessionId;
    private final String clientIp;
    private volatile boolean authenticated;
    private volatile long roleId;
    private volatile String backendServiceId;

    public GatewaySession(IConnection connection, String clientIp) {
        super(Objects.requireNonNull(connection, "connection"));
        this.sessionId = connection.getConnectionId();
        this.clientIp = normalizeIp(clientIp);
    }

    public long getSessionId() { return sessionId; }
    public String getClientIp() { return clientIp; }
    public boolean isAuthenticated() { return authenticated; }
    public void setAuthenticated(boolean authenticated) { this.authenticated = authenticated; }
    public long getRoleId() { return roleId; }
    public void setRoleId(long roleId) {
        if (roleId < 0) throw new IllegalArgumentException("roleId must be non-negative");
        this.roleId = roleId;
    }
    public String getBackendServiceId() { return backendServiceId; }
    public void setBackendServiceId(String backendServiceId) {
        this.backendServiceId = backendServiceId == null || backendServiceId.isBlank() ? null : backendServiceId;
    }
    public long routeKey() { return roleId > 0 ? roleId : sessionId; }

    private static String normalizeIp(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String address = value.trim();
        if (address.charAt(0) == '/') address = address.substring(1);
        if (address.startsWith("[") && address.contains("]")) return address.substring(1, address.indexOf(']'));
        int colon = address.lastIndexOf(':');
        if (colon > 0 && address.indexOf(':') == colon) return address.substring(0, colon);
        return address;
    }
}
