package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Connection admission, IP/session rate limiting, and login gating in one hot-path component. */
public final class GatewayGuard {
    private final int loginCommand;
    private final int maxConnectionsPerIp;
    private final double sessionRate;
    private final double sessionBurst;
    private final double ipRate;
    private final double ipBurst;
    private final ConcurrentHashMap<String, IpState> ipStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, SessionState> sessionStates = new ConcurrentHashMap<>();

    public GatewayGuard(int loginCommand, int maxConnectionsPerIp,
                        double sessionRate, double sessionBurst,
                        double ipRate, double ipBurst) {
        if (loginCommand <= 0) throw new IllegalArgumentException("loginCommand must be positive");
        if (maxConnectionsPerIp < 1) throw new IllegalArgumentException("maxConnectionsPerIp must be positive");
        requirePositive(sessionRate, "sessionRate");
        requirePositive(sessionBurst, "sessionBurst");
        requirePositive(ipRate, "ipRate");
        requirePositive(ipBurst, "ipBurst");
        this.loginCommand = loginCommand;
        this.maxConnectionsPerIp = maxConnectionsPerIp;
        this.sessionRate = sessionRate;
        this.sessionBurst = sessionBurst;
        this.ipRate = ipRate;
        this.ipBurst = ipBurst;
    }

    public boolean onConnect(GatewaySession session) {
        IpState[] accepted = new IpState[1];
        ipStates.compute(session.getClientIp(), (ip, current) -> {
            IpState state = current == null ? new IpState(ipRate, ipBurst) : current;
            if (state.connections.incrementAndGet() <= maxConnectionsPerIp) {
                accepted[0] = state;
                return state;
            }
            state.connections.decrementAndGet();
            return state.connections.get() == 0 ? null : state;
        });
        if (accepted[0] == null) return false;

        SessionState state = new SessionState(session.getClientIp(), accepted[0], sessionRate, sessionBurst);
        if (sessionStates.putIfAbsent(session.getSessionId(), state) == null) return true;
        releaseIp(state);
        return false;
    }

    public int onPacket(GatewaySession session, GatewayPacket packet) {
        SessionState state = sessionStates.get(session.getSessionId());
        if (state == null || !state.ipState.bucket.tryConsume()) return GatewayErrorCode.CONNECTION_LIMITED;
        if (!state.bucket.tryConsume()) return GatewayErrorCode.RATE_LIMITED;
        if (!session.isAuthenticated() && packet.getCommand() != loginCommand) {
            return GatewayErrorCode.NOT_LOGGED_IN;
        }
        return GatewayErrorCode.OK;
    }

    public void onDisconnect(GatewaySession session) {
        SessionState state = sessionStates.remove(session.getSessionId());
        if (state != null) releaseIp(state);
    }

    private void releaseIp(SessionState session) {
        ipStates.computeIfPresent(session.ip, (ignored, current) -> {
            if (current != session.ipState) return current;
            return current.connections.decrementAndGet() == 0 ? null : current;
        });
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static final class IpState {
        final AtomicInteger connections = new AtomicInteger();
        final TokenBucket bucket;

        IpState(double rate, double burst) { bucket = new TokenBucket(rate, burst); }
    }

    private record SessionState(String ip, IpState ipState, TokenBucket bucket) {
        SessionState(String ip, IpState ipState, double rate, double burst) {
            this(ip, ipState, new TokenBucket(rate, burst));
        }
    }
}
