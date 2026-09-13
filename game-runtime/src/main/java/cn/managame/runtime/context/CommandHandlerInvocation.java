package cn.managame.runtime.context;

import java.util.Objects;

/** Decoded command input. A requestId of zero means no response correlation. */
public record CommandHandlerInvocation(Object request, Object connection, int requestId, Metadata metadata)
        implements HandlerInvocation {
    public CommandHandlerInvocation {
        Objects.requireNonNull(request);
        Objects.requireNonNull(metadata);
        if (requestId < 0) throw new IllegalArgumentException("requestId must not be negative");
    }

    /** Local or one-way command without response correlation. */
    public CommandHandlerInvocation(Object request, Object connection, Metadata metadata) {
        this(request, connection, 0, metadata);
    }
}
