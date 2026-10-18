package cn.managame.runtime.event;

import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.executor.RouteExecuteStatus;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.route.RouteCallback;
import cn.managame.runtime.route.RouteDomain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.managame.core.FrameworkErrorCodes.ROUTE_DOMAIN_MISMATCH;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A listener runs on the publishing event's Route, so the Domain it declares must be that event's. The
 * declaration is what makes the rule checkable: it is verified at build where one event type's listeners
 * disagree, and at publish where an event reaches listeners that own state in another Domain.
 */
class EventDomainTest {
    record Notice(int routeDomain, long routeKey) implements Event {}
    record GuildSnapshot(long guildId, int members) {}

    static class PlayerListener {
        final List<Integer> seen = new ArrayList<>();
        @EventMethod(domain = 1) public void onNotice(Notice event) { seen.add(Contexts.current().routeDomain()); }
    }

    static class UndeclaredListener {
        final List<Integer> seen = new ArrayList<>();
        @EventMethod public void onNotice(Notice event) { seen.add(Contexts.current().routeDomain()); }
    }

    static class GuildListener {
        final List<Integer> seen = new ArrayList<>();
        @EventMethod(domain = 2) public void onNotice(Notice event) { seen.add(Contexts.current().routeDomain()); }
    }

    static class UnregisteredDomainListener {
        @EventMethod(domain = 9) public void onNotice(Notice event) {}
    }

    /** The sanctioned way to touch another Domain's state from a listener. */
    static class BridgingListener {
        volatile GameRuntime runtime;
        final List<String> steps = new ArrayList<>();
        @EventMethod(domain = 1) public void onNotice(Notice event) {
            steps.add("listener@" + Contexts.current().routeDomain());
            runtime.call(2, 77L, () -> {
                steps.add("compute@" + Contexts.current().routeDomain());
                return new GuildSnapshot(77L, 3);
            }, new RouteCallback<GuildSnapshot>() {
                @Override public void onSuccess(GuildSnapshot snapshot) {
                    steps.add("callback@" + Contexts.current().routeDomain() + ":" + snapshot.members());
                }
                @Override public void onFail(int errorCode) { steps.add("fail:" + errorCode); }
            });
        }
    }

    private final AtomicInteger submissions = new AtomicInteger();

    private GameRuntimeBuilder builder(Object... listeners) {
        return GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "player"), RouteDomain.of(2, "guild")))
                .routeExecutors(List.of(RouteExecutorBinding.of((d, k, task) -> {
                    submissions.incrementAndGet(); task.run(); return RouteExecuteStatus.ACCEPTED;
                }, 1, 2)))
                .eventHandlers(List.of(listeners));
    }

    @Test void aListenerRunsWhenTheEventCarriesTheDomainItDeclared() {
        var listener = new PlayerListener();
        try (var runtime = builder(listener).build()) {
            runtime.eventBus().publish(new Notice(1, 7));
            assertEquals(List.of(1), listener.seen);
            assertEquals(1, submissions.get());
        }
    }

    @Test void anEventForAnotherDomainIsRejectedBeforeAnyListenerOrRouteTask() {
        var listener = new GuildListener();
        try (var runtime = builder(listener).build()) {
            assertEquals(ROUTE_DOMAIN_MISMATCH, assertThrows(RuntimeDispatchException.class,
                    () -> runtime.eventBus().publish(new Notice(1, 7))).errorCode());
            assertTrue(listener.seen.isEmpty());
            assertEquals(0, submissions.get());
            // The Domain it declared is accepted, on the same Runtime and the same listener set.
            runtime.eventBus().publish(new Notice(2, 7));
            assertEquals(List.of(2), listener.seen);
            assertEquals(1, submissions.get());
        }
    }

    @Test void anUndeclaredListenerKeepsAcceptingWhateverDomainPublishes() {
        var listener = new UndeclaredListener();
        try (var runtime = builder(listener).build()) {
            runtime.eventBus().publish(new Notice(1, 7));
            runtime.eventBus().publish(new Notice(2, 7));
            assertEquals(List.of(1, 2), listener.seen);
            assertEquals(2, submissions.get());
        }
    }

    @Test void oneDeclaringListenerConstrainsTheEventForTheUndeclaredOnesToo() {
        var declared = new PlayerListener(); var undeclared = new UndeclaredListener();
        try (var runtime = builder(declared, undeclared).build()) {
            runtime.eventBus().publish(new Notice(1, 7));
            assertEquals(List.of(1), declared.seen); assertEquals(List.of(1), undeclared.seen);
            assertEquals(ROUTE_DOMAIN_MISMATCH, assertThrows(RuntimeDispatchException.class,
                    () -> runtime.eventBus().publish(new Notice(2, 7))).errorCode());
            assertEquals(List.of(1), undeclared.seen);
            assertEquals(1, submissions.get());
        }
    }

    @Test void listenersOfOneEventThatDisagreeFailAtBuild() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> builder(new PlayerListener(), new GuildListener()).build());
        assertTrue(failure.getMessage().contains("conflicting domains"), failure.getMessage());
    }

    @Test void anUnregisteredDeclaredDomainFailsAtBuild() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> builder(new UnregisteredDomainListener()).build());
        assertTrue(failure.getMessage().contains("Unregistered event listener domain"), failure.getMessage());
    }

    @Test void crossDomainWorkLeavesTheEventsRouteThroughACall() {
        var listener = new BridgingListener();
        try (var runtime = builder(listener).build()) {
            listener.runtime = runtime;
            runtime.eventBus().publish(new Notice(1, 7));
            // The listener stays on the event's Route; only the computation enters the guild Route, and the
            // callback comes back to the event's own context.
            assertEquals(List.of("listener@1", "compute@2", "callback@1:3"), listener.steps);
        }
    }
}
