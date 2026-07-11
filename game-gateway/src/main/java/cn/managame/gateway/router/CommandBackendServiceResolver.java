package cn.managame.gateway.router;

import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Routes command values or inclusive command ranges to logical service names. */
public final class CommandBackendServiceResolver implements BackendServiceResolver {
    private final String defaultService;
    private final List<Route> routes;

    public CommandBackendServiceResolver(String defaultService, List<Route> routes) {
        this.defaultService = requireText(defaultService, "defaultService");
        this.routes = List.copyOf(routes);
        validateNoOverlap(this.routes);
    }

    /** Parses {@code 1000=auth-service,2000-2999=scene-service,3000=chat-service}. */
    public static CommandBackendServiceResolver parse(String defaultService, String specification) {
        if (specification == null || specification.isBlank()) {
            return new CommandBackendServiceResolver(defaultService, List.of());
        }
        List<Route> routes = new ArrayList<>();
        for (String rawEntry : specification.split(",")) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) continue;
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("invalid backend route: " + entry);
            }
            String commandPart = entry.substring(0, separator).trim();
            String service = entry.substring(separator + 1).trim();
            int dash = commandPart.indexOf('-');
            int start = Integer.parseInt(dash < 0 ? commandPart : commandPart.substring(0, dash).trim());
            int end = Integer.parseInt(dash < 0 ? commandPart : commandPart.substring(dash + 1).trim());
            routes.add(new Route(start, end, service));
        }
        return new CommandBackendServiceResolver(defaultService, routes);
    }

    @Override
    public String resolve(GatewaySession session, GatewayPacket packet) {
        int command = packet.getCommand();
        for (Route route : routes) if (route.matches(command)) return route.serviceName();
        return defaultService;
    }

    public Set<String> serviceNames() {
        Set<String> names = new LinkedHashSet<>();
        names.add(defaultService);
        routes.forEach(route -> names.add(route.serviceName()));
        return Set.copyOf(names);
    }

    public record Route(int startCommand, int endCommand, String serviceName) {
        public Route {
            if (startCommand <= 0 || endCommand < startCommand) {
                throw new IllegalArgumentException("invalid command range: " + startCommand + '-' + endCommand);
            }
            serviceName = requireText(serviceName, "serviceName");
        }
        boolean matches(int command) { return command >= startCommand && command <= endCommand; }
    }

    private static void validateNoOverlap(List<Route> routes) {
        for (int i = 0; i < routes.size(); i++) {
            for (int j = i + 1; j < routes.size(); j++) {
                Route a = routes.get(i), b = routes.get(j);
                if (a.startCommand() <= b.endCommand() && b.startCommand() <= a.endCommand()) {
                    throw new IllegalArgumentException("overlapping backend command routes: " + a + " and " + b);
                }
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
