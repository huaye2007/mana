package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.context.CommandContext;
import cn.managame.runtime.context.CommandHandlerInvocation;
import cn.managame.runtime.context.Metadata;
import cn.managame.runtime.context.MetadataKey;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.protocol.ProtocolType;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static cn.managame.runtime.execution.RouteRuntimeTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandFailureStageTest {
    record Request(long player) {}
    @Handler(routeType = TestRoutes.Player.class)
    static class Broken {
        @HandlerMethod public void run(Request request) { throw new IllegalStateException("business"); }
    }

    @Test void allCommandFailureStagesRetainOriginalInvocationAndReportOnce() throws Exception {
        var errors = new CopyOnWriteArrayList<HandlerException>();
        var metadata = Metadata.empty().with(MetadataKey.application(250, String.class), "request-41");
        var invocation = new CommandHandlerInvocation(new Request(7), new Object(), 41, metadata);
        var protocols = ProtocolRegistry.builder().register(1, ProtocolType.REQUEST, Request.class).build();
        try (var runtime = builder().protocols(protocols).handler(new Broken()).exceptionHandler(errors::add)
                .defaultRoute(TestRoutes.Player.class, Request.class, Request::player).build()) {
            var execution = assertThrows(ExecutionException.class, () -> done(runtime.command(invocation)));
            done(runtime.dispatch(TestRoutes.Player.class, 7, () -> {}));
            assertEquals(1, errors.size());
            assertSame(execution.getCause(), errors.getFirst());
            assertSame(invocation, errors.getFirst().invocation());
            assertEquals(41, errors.getFirst().invocation().requestId());
            assertEquals(41, ((CommandContext) errors.getFirst().context()).requestId());
            assertEquals(HandlerException.CommandStage.EXECUTION, errors.getFirst().commandStage());
            assertSame(invocation, ((CommandContext) errors.getFirst().context()).invocation());
            runtime.close();
            var admission = assertThrows(HandlerException.class, () -> runtime.command(invocation));
            assertEquals(2, errors.size());
            assertSame(admission, errors.getLast());
            assertSame(invocation, admission.invocation());
            assertEquals(41, admission.invocation().requestId());
            assertEquals(41, ((CommandContext) admission.context()).requestId());
            assertNotNull(admission.context());
            assertEquals(HandlerException.CommandStage.ADMISSION, admission.commandStage());
        }
        errors.clear();
        try (var runtime = builder().protocols(protocols).handler(new Broken()).exceptionHandler(errors::add)
                .defaultRoute(TestRoutes.Player.class, Request.class, request -> { throw new IllegalArgumentException("identity"); }).build()) {
            var resolution = assertThrows(HandlerException.class, () -> runtime.command(invocation));
            assertEquals(1, errors.size());
            assertSame(resolution, errors.getFirst());
            assertSame(invocation, resolution.invocation());
            assertEquals(41, resolution.invocation().requestId());
            assertSame(metadata, resolution.invocation().metadata());
            assertNull(resolution.context());
            assertEquals(HandlerException.CommandStage.RESOLUTION, resolution.commandStage());
        }
    }
}
