package cn.managame.demo.server.runtime;

import cn.managame.runtime.context.CommandHandlerInvocation;
import cn.managame.runtime.context.Metadata;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.diagnostics.RuntimeShutdownException;
import cn.managame.runtime.execution.ExecutionDomain;
import cn.managame.runtime.execution.GameRuntime;
import cn.managame.runtime.execution.ParameterResolverRegistry;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.protocol.ProtocolType;

import cn.managame.demo.protocol.CommandBinding;

import cn.managame.demo.server.support.*;
import cn.managame.network.Connection;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.runtime.execution.*;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;


/** Configures player execution and dispatches commands; concrete handlers are supplied by composition. */
public final class ServerRuntime implements AutoCloseable {
    private final GameRuntime engine;
    private final Map<Integer, CommandBinding<?, ?>> commands;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public ServerRuntime(int nodeId, Collection<CommandBinding<?, ?>> commands, Object... handlers) {
        this.commands = commands.stream().collect(Collectors.toUnmodifiableMap(CommandBinding::id, binding -> binding));
        var protocols = ProtocolRegistry.builder();
        this.commands.values().forEach(binding -> protocols.register(binding.id(), ProtocolType.REQUEST, binding.requestType()));
        var builder = GameRuntime.builder()
                .executionDomain(PlayerRoute.class, ExecutionDomain.platform("players-" + nodeId).threads(2).build())
                .protocols(protocols.build())
                .parameters(ParameterResolverRegistry.builder().registerRouteSource(PlayerId.class,
                        invocation -> new PlayerId(invocation.metadata().get(CommandMetadata.PLAYER_ID))).build())
                .defaultRoute(PlayerRoute.class, PlayerId.class, PlayerId::value)
                .exceptionHandler(failure -> {
                    var invocation = failure.invocation();
                    if (invocation != null && invocation.connection() instanceof Connection connection) {
                        var error = GameFailures.from(failure);
                        GameMessages.sendError(connection, invocation.requestId(),
                                invocation.metadata().get(CommandMetadata.COMMAND_ID),
                                error.errorCode(), error.errorArgs().toArray(String[]::new));
                    }
                    System.getLogger(ServerRuntime.class.getName()).log(System.Logger.Level.WARNING,
                            "Runtime command failed", failure);
                });
        for (Object handler : handlers) builder.handler(handler);
        engine = builder.build();
    }

    public GameRuntime engine() { return engine; }
    public CommandBinding<?, ?> command(int id) { return commands.get(id); }

    public void submit(int commandId, Object request, Connection connection, int requestId, Metadata metadata) {
        if (!accepting.get()) {
            GameMessages.sendError(connection, requestId, commandId, RpcError.UNAVAILABLE.code());
            return;
        }
        CommandBinding<?, ?> binding = commands.get(commandId);
        if (binding == null) {
            GameMessages.sendError(connection, requestId, commandId, RpcError.NO_HANDLER.code());
            return;
        }
        if (!binding.requestType().isInstance(request) || metadata.get(CommandMetadata.PLAYER_ID) <= 0) {
            GameMessages.sendError(connection, requestId, commandId, RpcError.PROTOCOL_ERROR.code());
            return;
        }
        try {
            engine.command(new CommandHandlerInvocation(request, connection, requestId, metadata.with(CommandMetadata.COMMAND_ID, commandId)));
        } catch (HandlerException reported) {
            // The exception handler owns the reply for resolution, admission and execution failures.
        }
    }

    public void stopAdmission() { accepting.set(false); }
    public void close(Duration timeout) {
        stopAdmission();
        var report = engine.close(timeout);
        if (!report.terminated()) throw new RuntimeShutdownException(report);
    }
    @Override public void close() { close(Duration.ofSeconds(30)); }
}
