package cn.managame.runtime.context;

import cn.managame.core.Metadata;

/** Stores decoded message and immutable envelope values; owns no RPC buffer or connection. */
public class DefaultRpcHandlerContext extends DefaultHandlerContext implements RpcHandlerContext {
    private final int sourceNodeId;
    private final int sourceSlotId;
    private final int command;
    private final int requestId;

    public DefaultRpcHandlerContext(int domain, long key, int businessIdType, long businessId,
                                    Metadata metadata, Object message, int sourceNodeId,
                                    int sourceSlotId, int command, int requestId) {
        super(domain, key, businessIdType, businessId, metadata, message);
        if (sourceNodeId == 0) throw new IllegalArgumentException("sourceNodeId must be nonzero");
        if (sourceSlotId < 0 || sourceSlotId > 254) throw new IllegalArgumentException("sourceSlotId must be 0..254");
        if (command == 0) throw new IllegalArgumentException("command must be nonzero");
        this.sourceNodeId = sourceNodeId;
        this.sourceSlotId = sourceSlotId;
        this.command = command;
        this.requestId = requestId;
    }

    @Override public int sourceNodeId() { return sourceNodeId; }
    @Override public int sourceSlotId() { return sourceSlotId; }
    @Override public int command() { return command; }
    @Override public int requestId() { return requestId; }
}
