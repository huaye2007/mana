package cn.managame.runtime.diagnostics;

import cn.managame.runtime.context.CommandContext;
import cn.managame.runtime.context.CommandHandlerInvocation;
import cn.managame.runtime.context.HandlerContext;

public final class HandlerException extends RuntimeException {
    public enum CommandStage { RESOLUTION, ADMISSION, EXECUTION }
    private final HandlerContext context;
    private final String source;
    private final CommandHandlerInvocation invocation;
    private final CommandStage commandStage;

    public HandlerException(String source, HandlerContext context, Throwable cause) {
        this(source, context, cause,
                context instanceof CommandContext command ? command.invocation() : null,
                context instanceof CommandContext ? CommandStage.EXECUTION : null);
    }

    public HandlerException(String source, HandlerContext context, Throwable cause,
            CommandHandlerInvocation invocation, CommandStage commandStage) {
        super("Handler failure in " + source + (context == null ? "" : " at " + context.route()), cause);
        this.source = source; this.context = context; this.invocation = invocation; this.commandStage = commandStage;
    }
    public String source() { return source; }
    public HandlerContext context() { return context; }
    /** Null for invocations other than commands. */
    public CommandHandlerInvocation invocation() { return invocation; }
    /** Null for invocations other than commands. */
    public CommandStage commandStage() { return commandStage; }
}
