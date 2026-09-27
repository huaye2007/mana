package cn.managame.runtime.context;

import cn.managame.core.*;
import java.util.Objects;
public class DefaultInvocationContext extends DefaultContext implements InvocationContext {
    private final int businessIdType; private final long businessId; private final Metadata metadata;
    public DefaultInvocationContext(int domain, long key, int businessIdType, long businessId, Metadata metadata) {
        super(domain, key);
        if (businessIdType < 0 || businessIdType > 255) throw new IllegalArgumentException("businessIdType must be uint8");
        this.businessIdType = businessIdType; this.businessId = businessId; this.metadata = Objects.requireNonNull(metadata);
    }
    public int businessIdType() { return businessIdType; }
    public long businessId() { return businessId; }
    public Metadata metadata() { return metadata; }
}
