package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Built-in filters. Applications can compose these with their own {@link GatewayFilter}. */
public final class GatewayFilters {
    private GatewayFilters() { }

    public static GatewayFilter ipProtection(int maxConnectionsPerIp, double packetsPerSecond, double burst) {
        requirePositive(packetsPerSecond, "packetsPerSecond");
        requirePositive(burst, "burst");
        return new IpProtectionFilter(maxConnectionsPerIp, packetsPerSecond, burst);
    }

    public static GatewayFilter sessionRateLimit(double packetsPerSecond, double burst) {
        requirePositive(packetsPerSecond, "packetsPerSecond");
        requirePositive(burst, "burst");
        return new SessionRateLimitFilter(packetsPerSecond, burst);
    }

    public static GatewayFilter loginRequired(int loginCommand) {
        return new LoginRequiredFilter(loginCommand);
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static final class IpProtectionFilter implements GatewayFilter {
        private final int maxConnectionsPerIp;
        private final double packetsPerSecond;
        private final double burst;
        private final ConcurrentHashMap<String, IpState> states = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Admission> admissions = new ConcurrentHashMap<>();

        IpProtectionFilter(int maxConnectionsPerIp, double packetsPerSecond, double burst) {
            if (maxConnectionsPerIp < 1) {
                throw new IllegalArgumentException("maxConnectionsPerIp must be positive");
            }
            this.maxConnectionsPerIp = maxConnectionsPerIp;
            this.packetsPerSecond = packetsPerSecond;
            this.burst = burst;
        }

        @Override
        public boolean onConnect(GatewaySession session) {
            IpState[] accepted = new IpState[1];
            String ip = session.getClientIp();
            states.compute(ip, (ignored, current) -> {
                IpState state = current == null
                        ? new IpState(new TokenBucket(packetsPerSecond, burst)) : current;
                if (state.connections.incrementAndGet() <= maxConnectionsPerIp) {
                    accepted[0] = state;
                    return state;
                }
                state.connections.decrementAndGet();
                return state.connections.get() == 0 ? null : state;
            });
            if (accepted[0] == null) return false;

            Admission admission = new Admission(ip, accepted[0]);
            if (admissions.putIfAbsent(session.getSessionId(), admission) == null) return true;
            release(admission);
            return false;
        }

        @Override
        public int onPacket(GatewaySession session, GatewayPacket packet) {
            Admission admission = admissions.get(session.getSessionId());
            return admission != null && admission.state().bucket.tryAcquire()
                    ? GatewayErrorCode.OK : GatewayErrorCode.CONNECTION_LIMITED;
        }

        @Override
        public void onDisconnect(GatewaySession session) {
            Admission admission = admissions.remove(session.getSessionId());
            if (admission != null) release(admission);
        }

        private void release(Admission admission) {
            states.computeIfPresent(admission.ip(), (ignored, current) -> {
                if (current != admission.state()) return current;
                return current.connections.decrementAndGet() == 0 ? null : current;
            });
        }
    }

    private static final class SessionRateLimitFilter implements GatewayFilter {
        private final double packetsPerSecond;
        private final double burst;
        private final ConcurrentHashMap<Long, TokenBucket> buckets = new ConcurrentHashMap<>();

        SessionRateLimitFilter(double packetsPerSecond, double burst) {
            this.packetsPerSecond = packetsPerSecond;
            this.burst = burst;
        }

        @Override
        public boolean onConnect(GatewaySession session) {
            long sessionId = session.getSessionId();
            return buckets.putIfAbsent(sessionId,
                    new TokenBucket(packetsPerSecond, burst)) == null;
        }

        @Override
        public int onPacket(GatewaySession session, GatewayPacket packet) {
            TokenBucket bucket = buckets.get(session.getSessionId());
            return bucket != null && bucket.tryAcquire()
                    ? GatewayErrorCode.OK : GatewayErrorCode.RATE_LIMITED;
        }

        @Override
        public void onDisconnect(GatewaySession session) { buckets.remove(session.getSessionId()); }
    }

    private static final class LoginRequiredFilter implements GatewayFilter {
        private final int loginCommand;

        LoginRequiredFilter(int loginCommand) {
            if (loginCommand <= 0) throw new IllegalArgumentException("loginCommand must be positive");
            this.loginCommand = loginCommand;
        }

        @Override
        public int onPacket(GatewaySession session, GatewayPacket packet) {
            return session.isAuthenticated() || packet.getCommand() == loginCommand
                    ? GatewayErrorCode.OK : GatewayErrorCode.NOT_LOGGED_IN;
        }
    }

    private static final class IpState {
        final AtomicInteger connections = new AtomicInteger();
        final TokenBucket bucket;

        IpState(TokenBucket bucket) { this.bucket = bucket; }
    }

    private record Admission(String ip, IpState state) { }
}
