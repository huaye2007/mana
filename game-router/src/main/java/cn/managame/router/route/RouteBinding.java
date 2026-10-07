package cn.managame.router.route;

/** Logical owner; the service and binding key live in the map key. */
public record RouteBinding(int nodeId, long nodeEpoch) {
    public RouteBinding {
        if (nodeId == 0 || nodeEpoch == 0) throw new IllegalArgumentException("nodeId/epoch must be nonzero");
    }
}
