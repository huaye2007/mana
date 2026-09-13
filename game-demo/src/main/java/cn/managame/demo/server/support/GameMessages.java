package cn.managame.demo.server.support;

import cn.managame.network.AttributeKey;
import cn.managame.network.Connection;
import cn.managame.runtime.context.CommandContext;
import cn.managame.runtime.execution.HandlerContexts;

import java.util.Objects;

/** Response and client notification facade; transport adapters own wire encoding. */
public final class GameMessages {
    public interface Sender {
        boolean send(Connection connection, int requestId, int commandId, Object message);
        boolean sendError(Connection connection, int requestId, int commandId, int code, String... args);

        /** Only client transports support game-client notifications. */
        default boolean notify(Connection connection, Object message) {
            throw new UnsupportedOperationException("This connection does not support client notifications");
        }
    }
    private static final AttributeKey<Sender> SENDER = AttributeKey.of("demo.messageSender", Sender.class);

    public static void bind(Connection connection, Sender sender) {
        Objects.requireNonNull(sender);
        if (!connection.compareAndSet(SENDER, null, sender) && connection.get(SENDER) != sender)
            throw new IllegalStateException("Connection already has another message sender");
    }

    public static boolean send(Object message) {
        var context = context();
        return send((Connection) context.connection(), context.requestId(),
                context.command().id(), message);
    }

    /** Push to the current command's client connection without correlating to that command. */
    public static boolean notify(Object message) {
        return notify((Connection) context().connection(), message);
    }

    /** Push outside a command context, for example from a timer using a saved client connection. */
    public static boolean notify(Connection connection, Object message) {
        return sender(connection).notify(connection, Objects.requireNonNull(message));
    }

    public static boolean sendError(int code, String... args) {
        var context = context();
        return sendError((Connection) context.connection(), context.requestId(),
                context.command().id(), code, args);
    }

    public static boolean send(Connection connection, int requestId, int commandId, Object message) {
        return sender(connection).send(connection, requestId, commandId, Objects.requireNonNull(message));
    }

    public static boolean sendError(Connection connection, int requestId, int commandId, int code, String... args) {
        if (code <= 0) throw new IllegalArgumentException("Error code must be positive");
        return sender(connection).sendError(connection, requestId, commandId, code, args);
    }

    private static Sender sender(Connection connection) {
        return Objects.requireNonNull(connection.get(SENDER), "No message sender bound to connection");
    }

    private static CommandContext context() {
        if (!(HandlerContexts.currentOrNull() instanceof CommandContext context))
            throw new IllegalStateException("Sending on the current connection requires a current command context");
        if (!(context.connection() instanceof Connection))
            throw new IllegalStateException("Current command has no game-network connection");
        return context;
    }
    private GameMessages() {}
}
