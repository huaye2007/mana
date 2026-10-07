package cn.managame.router.route;

/** A logical Node attachment serves exactly one service. */
public record NodeRegistration(int nodeId, long nodeEpoch, int serviceId) {
    public NodeRegistration {
        if (nodeId == 0 || nodeEpoch == 0 || serviceId <= 0)
            throw new IllegalArgumentException("invalid node registration");
    }
}
