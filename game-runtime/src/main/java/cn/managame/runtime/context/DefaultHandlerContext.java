package cn.managame.runtime.context;

import cn.managame.core.*;
import cn.managame.network.connection.Connection;
import java.util.Objects;
public class DefaultHandlerContext extends DefaultInvocationContext implements HandlerContext {
    private final Object message;
    private final Connection connection;
    public DefaultHandlerContext(int domain, long key, Object message) { this(domain, key, 0, 0, Metadatas.empty(), message); }
    public DefaultHandlerContext(int domain, long key, Object message, Connection connection) {
        this(domain, key, 0, 0, Metadatas.empty(), message, connection);
    }
    public DefaultHandlerContext(int domain, long key, int businessIdType, long businessId, Metadata metadata, Object message) {
        this(domain, key, businessIdType, businessId, metadata, message, null);
    }
    public DefaultHandlerContext(int domain, long key, int businessIdType, long businessId, Metadata metadata, Object message, Connection connection) {
        super(domain, key, businessIdType, businessId, metadata);
        this.message = Objects.requireNonNull(message); this.connection = connection;
    }
    public Object message() { return message; }
    public Connection connection() { return connection; }
}
