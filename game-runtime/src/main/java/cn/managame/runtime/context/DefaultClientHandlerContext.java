package cn.managame.runtime.context;

import cn.managame.core.Metadata;
import cn.managame.network.connection.Connection;

public class DefaultClientHandlerContext extends DefaultHandlerContext implements ClientHandlerContext {
    private final Connection connection;
    private final int command;
    private final int requestId;

    /** Without correlation: the request expects no response. */
    public DefaultClientHandlerContext(int domain, long key, Object message, Connection connection) {
        this(domain, key, 0, 0, Metadata.empty(), message, connection);
    }

    /** Without correlation: the request expects no response. */
    public DefaultClientHandlerContext(int domain, long key, int businessIdType, long businessId,
                                       Metadata metadata, Object message, Connection connection) {
        this(domain, key, businessIdType, businessId, metadata, message, connection, 0, 0);
    }

    public DefaultClientHandlerContext(int domain, long key, int businessIdType, long businessId,
                                       Metadata metadata, Object message, Connection connection,
                                       int command, int requestId) {
        super(domain, key, businessIdType, businessId, metadata, message);
        this.connection = connection;
        this.command = command;
        this.requestId = requestId;
    }

    @Override public Connection connection() { return connection; }
    @Override public int command() { return command; }
    @Override public int requestId() { return requestId; }
}
