package cn.managame.router.error;

import cn.managame.core.FrameworkErrorCodes;

public final class RouterErrorCodes {
    private RouterErrorCodes() {}
    public static final int ROUTE_NOT_FOUND = FrameworkErrorCodes.ROUTER_ROUTE_NOT_FOUND;
    public static final int BINDING_CONFLICT = FrameworkErrorCodes.ROUTER_BINDING_CONFLICT;
    public static final int NOT_REGISTERED = FrameworkErrorCodes.ROUTER_NOT_REGISTERED;
    public static final int INVALID_SERVICE = FrameworkErrorCodes.ROUTER_INVALID_SERVICE;
}
