package cn.managame.runtime.context;

import cn.managame.core.Metadata;
import cn.managame.core.Metadatas;
import cn.managame.network.connection.Connection;

public class DefaultClientHandlerContext extends DefaultHandlerContext implements ClientHandlerContext {
    private final Connection connection;

    public DefaultClientHandlerContext(int domain, long key, Object message, Connection connection) {
        this(domain, key, 0, 0, Metadatas.empty(), message, connection);
    }

    public DefaultClientHandlerContext(int domain, long key, int businessIdType, long businessId,
                                       Metadata metadata, Object message, Connection connection) {
        super(domain, key, businessIdType, businessId, metadata, message);
        this.connection = connection;
    }

    @Override public Connection connection() { return connection; }
}
