package cn.managame.demo.server.support;


import cn.managame.runtime.context.Metadata;
import cn.managame.runtime.context.MetadataKey;

/** Player routing and tracing extensions. requestId belongs to CommandHandlerInvocation. */
public final class CommandMetadata {
    public static final MetadataKey<Long> PLAYER_ID = MetadataKey.application(201, Long.class);
    public static final MetadataKey<Integer> COMMAND_ID = MetadataKey.application(202, Integer.class);
    public static final MetadataKey<Long> TRACE_ID = MetadataKey.application(1024, Long.class);

    public static Metadata player(long playerId, long traceId) {
        return Metadata.empty().with(PLAYER_ID, playerId).with(TRACE_ID, traceId);
    }
    private CommandMetadata() {}
}
