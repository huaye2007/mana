package cn.managame.gateway.session;

import cn.managame.network.connection.IConnection;
import cn.managame.network.connection.IWriteCallback;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class GatewaySession {
    private final long sessionId;
    private final IConnection connection;
    private final String clientIp;
    private volatile boolean authenticated;
    private volatile long roleId;
    private final ConcurrentHashMap<String, String> backendBindings = new ConcurrentHashMap<>();

    public GatewaySession(long sessionId, IConnection connection, String clientIp) {
        if (sessionId <= 0) throw new IllegalArgumentException("sessionId must be positive");
        this.sessionId = sessionId;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.clientIp = normalizeIp(clientIp);
    }

    public long getSessionId() { return sessionId; }
    public IConnection getConnection() { return connection; }
    public void writeMsg(Object message) { connection.writeMsg(message); }
    public void writeMsg(Object message, IWriteCallback callback) { connection.writeMsg(message, callback); }
    public void close() { connection.close(); }
    public String getClientIp() { return clientIp; }
    public boolean isAuthenticated() { return authenticated; }
    public void setAuthenticated(boolean authenticated) { this.authenticated = authenticated; }
    public long getRoleId() { return roleId; }
    public void setRoleId(long roleId) {
        if (roleId < 0) throw new IllegalArgumentException("roleId must be non-negative");
        this.roleId = roleId;
    }
    public String getBackendServiceId(String serviceName) { return backendBindings.get(serviceName); }
    public void setBackendServiceId(String serviceName, String serviceId) {
        Objects.requireNonNull(serviceName, "serviceName");
        if (serviceId == null || serviceId.isBlank()) backendBindings.remove(serviceName);
        else backendBindings.put(serviceName, serviceId);
    }
    public void clearBackendBindings() { backendBindings.clear(); }
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
