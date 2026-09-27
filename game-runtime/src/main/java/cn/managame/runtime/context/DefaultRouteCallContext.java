package cn.managame.runtime.context;

import cn.managame.core.Metadata;
public class DefaultRouteCallContext extends DefaultInvocationContext implements RouteCallContext {
    public DefaultRouteCallContext(int domain, long key, int businessIdType, long businessId, Metadata metadata) {
        super(domain, key, businessIdType, businessId, metadata);
    }
}
