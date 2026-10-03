package cn.managame.runtime;

import cn.managame.core.FrameworkErrorCodes;
import cn.managame.core.Metadatas;
import cn.managame.network.connection.Connection;
import cn.managame.runtime.context.*;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.executor.*;
import cn.managame.runtime.handler.*;
import cn.managame.runtime.route.RouteKeyBinding;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HandlerContextFactoryTest {
    record Identity(long roleId, long guildId) {}
    record RoleId(long value) {}
    record GuildId(long value) {}
    record Received(Object identity, HandlerContext context) {}
    static class CustomContext extends DefaultClientHandlerContext {
        CustomContext(int domain, long key, int type, long id, Object message, Connection connection) {
            super(domain, key, type, id, Metadatas.empty(), message, connection);
        }
    }
    @Handler(domain = 1)
    static class Handlers {
        final List<Received> received = new ArrayList<>();
        @HandlerMethod public void role(RoleId role, HandlerDispatchTest.FieldRequest message, CustomContext context) {
            assertSame(context, Contexts.current()); received.add(new Received(role, context));
        }
        @HandlerMethod(domain = 2)
        public void guild(HandlerDispatchTest.MethodRequest message, HandlerContext context, GuildId guild) {
            assertSame(context, Contexts.current()); received.add(new Received(guild, context));
        }
    }

    static Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("Unexpected Connection operation: " + method.getName());
                });
    }
    static GameRuntimeBuilder builder(Handlers handlers, RouteExecutor executor, List<RuntimeError> errors) {
        return HandlerDispatchTest.builder(handlers, executor, errors).handlerArguments(List.of(
                HandlerArgumentBinding.of(RoleId.class, c -> {
                    assertEquals(7, c.businessIdType()); return new RoleId(c.businessId());
                }), HandlerArgumentBinding.of(GuildId.class, c -> {
                    assertEquals(8, c.businessIdType()); return new GuildId(c.businessId());
                })));
    }

    @Test void annotationDomainSelectsPolicyUsingExternalMapAndCapturesItsContextBeforeAdmission() {
        var roles = new ConcurrentHashMap<Connection, Identity>();
        Connection connection = connection(); roles.put(connection, new Identity(10001, 20002));
        var handlers = new Handlers(); var tasks = new ArrayDeque<Runnable>();
        var domains = new ArrayList<Integer>(); var contexts = new ArrayList<ClientHandlerContext>();
        var errors = new ArrayList<RuntimeError>(); var routes = new ArrayList<String>();
        try (var runtime = builder(handlers, (d, k, t) -> {
            routes.add(d + "/" + k); tasks.add(t); return RouteExecuteStatus.ACCEPTED;
        }, errors).routeKeys(List.of(RouteKeyBinding.of(HandlerDispatchTest.FieldRequest.class,
                message -> { throw new AssertionError("Factory dispatch must not extract message Key"); })))
                .handlerContextFactory((domain, peer, message) -> {
                    assertNull(Contexts.currentOrNull()); domains.add(domain);
                    var identity = Objects.requireNonNull(roles.get(peer));
                    var context = switch (domain) {
                        case 1 -> new CustomContext(domain, 99, 7, identity.roleId(), message, peer);
                        case 2 -> new CustomContext(domain, 88, 8, identity.guildId(), message, peer);
                        default -> throw new AssertionError("Unexpected Domain");
                    };
                    contexts.add(context); return context;
                }).build()) {
            var player = new HandlerDispatchTest.FieldRequest(42);
            var guild = new HandlerDispatchTest.MethodRequest(null);
            runtime.dispatch(connection, player); runtime.dispatch(connection, guild);
            assertEquals(List.of(1, 2), domains); assertEquals(List.of("1/99", "2/88"), routes);
            assertTrue(handlers.received.isEmpty()); roles.remove(connection);
            assertEquals(FrameworkErrorCodes.HANDLER_NOT_FOUND,
                    assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(connection, "unknown")).errorCode());
            assertEquals(2, domains.size());
            runtime.close();
            assertEquals(FrameworkErrorCodes.RUNTIME_CLOSED,
                    assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(connection, player)).errorCode());
            assertEquals(2, domains.size());
            while (!tasks.isEmpty()) tasks.remove().run();
            assertEquals(new RoleId(10001), handlers.received.get(0).identity());
            assertEquals(new GuildId(20002), handlers.received.get(1).identity());
            assertSame(contexts.get(0), handlers.received.get(0).context());
            assertSame(player, contexts.get(0).message()); assertSame(guild, contexts.get(1).message());
            assertSame(connection, contexts.get(0).connection()); assertTrue(errors.isEmpty());
        }
    }

    @Test void factoryErrorsAndChangedInputsRejectBeforeArgumentResolutionOrSubmission() {
        var submissions = new AtomicInteger(); var resolutions = new AtomicInteger();
        var errors = new ArrayList<RuntimeError>(); var message = new HandlerDispatchTest.FieldRequest(42);
        Connection connection = connection(); Connection replacement = connection();
        List<HandlerContextFactory> invalid = List.of(
                (d, c, m) -> null,
                (d, c, m) -> new CustomContext(2, 99, 7, 1, m, c),
                (d, c, m) -> new CustomContext(d, 99, 7, 1, new HandlerDispatchTest.FieldRequest(42), c),
                (d, c, m) -> new CustomContext(d, 99, 7, 1, m, replacement),
                (d, c, m) -> new CustomContext(d, 0, 7, 1, m, c),
                (d, c, m) -> new DefaultClientHandlerContext(d, 99, m, c));
        int[] expectedErrors = {0, FrameworkErrorCodes.ROUTE_DOMAIN_MISMATCH,
                FrameworkErrorCodes.HANDLER_CONTEXT_MISMATCH, FrameworkErrorCodes.HANDLER_CONTEXT_MISMATCH,
                FrameworkErrorCodes.INVALID_ROUTE_KEY, FrameworkErrorCodes.HANDLER_CONTEXT_MISMATCH};
        int index = 0;
        for (var factory : invalid) {
            try (var runtime = builder(new Handlers(), (d, k, t) -> {
                submissions.incrementAndGet(); return RouteExecuteStatus.ACCEPTED;
            }, errors).handlerArguments(List.of(HandlerArgumentBinding.of(RoleId.class, c -> {
                resolutions.incrementAndGet(); return new RoleId(c.businessId());
            }), HandlerArgumentBinding.of(GuildId.class, c -> new GuildId(c.businessId()))))
                    .handlerContextFactory(factory).build()) {
                if (index == 0) assertThrows(NullPointerException.class, () -> runtime.dispatch(connection, message));
                else assertEquals(expectedErrors[index], assertThrows(RuntimeDispatchException.class,
                        () -> runtime.dispatch(connection, message)).errorCode());
            }
            index++;
        }
        var failure = new IllegalArgumentException("No authenticated identity");
        try (var runtime = builder(new Handlers(), (d, k, t) -> {
            submissions.incrementAndGet(); return RouteExecuteStatus.ACCEPTED;
        }, errors).handlerContextFactory((d, c, m) -> { throw failure; }).build()) {
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> runtime.dispatch(connection, message)));
        }
        assertEquals(0, submissions.get()); assertEquals(0, resolutions.get()); assertTrue(errors.isEmpty());
    }

    @Test void explicitParametersAndIdentityAwareExtractionBypassFactory() {
        @Handler(domain = 1, routeKey = "userId") class MessageHandler {
            final List<HandlerContext> contexts = new ArrayList<>();
            @HandlerMethod public void handle(HandlerContext context, HandlerDispatchTest.FieldRequest request) {
                contexts.add(context);
            }
        }
        var handler = new MessageHandler(); var errors = new ArrayList<RuntimeError>();
        try (var runtime = HandlerDispatchTest.builder(handler, (d, k, t) -> {
            t.run(); return RouteExecuteStatus.ACCEPTED;
        }, errors).handlerContextFactory((d, c, m) -> { throw new AssertionError("Explicit dispatch must bypass factory"); }).build()) {
            var request = new HandlerDispatchTest.FieldRequest(42);
            runtime.dispatch(null, 99L, 7, 10001L, request);
            runtime.dispatch(null, 98L, request);
            runtime.dispatch(null, 7, 10002L, request);
            var context = new DefaultHandlerContext(1, 88, 9, 10003L, Metadatas.empty(), request);
            runtime.dispatch(context);
            assertEquals(List.of(99L, 98L, 42L, 88L), handler.contexts.stream().map(HandlerContext::routeKey).toList());
            assertEquals(List.of(10001L, 0L, 10002L, 10003L), handler.contexts.stream().map(HandlerContext::businessId).toList());
            assertSame(context, handler.contexts.getLast()); assertTrue(errors.isEmpty());
        }
    }

    @Test void closureDuringFactoryCreationRejectsAndInlineDispatchRestoresOuterContext() {
        var contexts = new ArrayList<ClientHandlerContext>();
        @Handler(domain = 1) class Nested {
            GameRuntime runtime;
            @HandlerMethod public void handle(ClientHandlerContext context, HandlerDispatchTest.FieldRequest request) {
                contexts.add(context);
                if (request.userId() == 1) {
                    runtime.dispatch(context.connection(), new HandlerDispatchTest.FieldRequest(2));
                    assertSame(context, Contexts.current());
                }
            }
        }
        var handler = new Nested(); var factoryScopes = new ArrayList<Context>(); var submissions = new AtomicInteger();
        var errors = new ArrayList<RuntimeError>();
        try (var runtime = HandlerDispatchTest.builder(handler, (d, k, t) -> {
            submissions.incrementAndGet(); t.run(); return RouteExecuteStatus.ACCEPTED;
        }, errors).handlerContextFactory((d, c, m) -> {
            factoryScopes.add(Contexts.currentOrNull());
            return new DefaultClientHandlerContext(d, 99, 7, ((HandlerDispatchTest.FieldRequest) m).userId(), Metadatas.empty(), m, c);
        }).build()) {
            handler.runtime = runtime; runtime.dispatch(null, new HandlerDispatchTest.FieldRequest(1));
            assertEquals(1, submissions.get()); assertEquals(2, contexts.size());
            assertNull(factoryScopes.getFirst()); assertSame(contexts.getFirst(), factoryScopes.getLast());
            assertEquals(2, contexts.getLast().businessId()); assertNull(Contexts.currentOrNull()); assertTrue(errors.isEmpty());
        }
        var runtimeRef = new GameRuntime[1];
        try (var runtime = HandlerDispatchTest.builder(handler, (d, k, t) -> {
            submissions.incrementAndGet(); return RouteExecuteStatus.ACCEPTED;
        }, errors).handlerContextFactory((d, c, m) -> {
            runtimeRef[0].close(); return new DefaultClientHandlerContext(d, 99, m, c);
        }).build()) {
            runtimeRef[0] = runtime;
            assertEquals(FrameworkErrorCodes.RUNTIME_CLOSED,
                    assertThrows(RuntimeDispatchException.class, () -> runtime.dispatch(null, new HandlerDispatchTest.FieldRequest(1))).errorCode());
            assertEquals(1, submissions.get());
        }
    }
}
