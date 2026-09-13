package cn.managame.runtime.context;

import cn.managame.runtime.protocol.Protocol;
import cn.managame.runtime.route.Route;

import java.util.Objects;

/** Transport-neutral command context. The decoded request belongs to the invocation. */
public final class CommandContext extends HandlerContext {
    private final Protocol command;
    private final Object connection;
    private final CommandHandlerInvocation invocation;

    public CommandContext(Route route, Metadata metadata, Protocol command, Object connection) {
        super(route, metadata);
        this.command = Objects.requireNonNull(command);
        this.connection = connection;
        this.invocation = null;
    }

    public CommandContext(Route route, Protocol command, CommandHandlerInvocation invocation) {
        super(route, Objects.requireNonNull(invocation).metadata());
        this.command = Objects.requireNonNull(command);
        this.connection = invocation.connection();
        this.invocation = invocation;
    }

    /** Original command input, available on contexts created by GameRuntime.command. */
    public CommandHandlerInvocation invocation() { return invocation; }
    /** Correlation from the original invocation; zero for local or one-way commands. */
    public int requestId() { return invocation == null ? 0 : invocation.requestId(); }
    public Protocol command() { return command; }
    public Object connection() { return connection; }
}
