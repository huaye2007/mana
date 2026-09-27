package cn.managame.runtime.context;

import cn.managame.core.*;
import java.util.Objects;
public class DefaultHandlerContext extends DefaultInvocationContext implements HandlerContext {
    private final Object message;
    public DefaultHandlerContext(int domain, long key, Object message) { this(domain, key, 0, 0, Metadatas.empty(), message); }
    public DefaultHandlerContext(int domain, long key, int businessIdType, long businessId, Metadata metadata, Object message) {
        super(domain, key, businessIdType, businessId, metadata); this.message = Objects.requireNonNull(message);
    }
    public Object message() { return message; }
}
