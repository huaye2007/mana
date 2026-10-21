# OGBS Server Composition Specification 1.0

**[English](OGBS-Boot-1.0.md)** | [简体中文](OGBS-Boot-1.0.zh-CN.md)

Language-independent responsibilities for the tier that joins capability components into a running server. The repository implements these in game-boot; companion: [Java development specification](OGBS-Boot-Java-25-Specification-1.0.md). The joined components' own contracts — [Runtime](OGBS-Runtime-1.0.md), [RPC](OGBS-RPC-1.0.md), [Data](OGBS-Data-1.0.md), [Network](OGBS-Network-1.0.md), [Core](OGBS-Core-1.0.md) — remain authoritative; nothing here weakens them.

## 1. What this tier is

A deployment needs more than the capability components. Something has to decide that an inbound RPC frame becomes a Route invocation, that a readiness probe is answered in front of business dispatch, and that the phases of a shutdown add up to one budget somebody chose. None of that belongs to a capability component: Runtime must not learn the RPC wire, RPC must not learn Routes, and Network must not learn the readings contract. Equally, none of it belongs to a container: it is the same work whether an application wires it by hand, through dependency injection or from a configuration file.

So the tier exists for the joins, and for nothing else. It is the only tier that is allowed to depend on several capability components at once, which is exactly why the adaptation lives here and the components stay unaware of each other.

**B-COMPOSE-01**: Every join defined here MUST be usable without a container. Each is an ordinary object an application constructs, holds and closes itself, from inputs it already has. A container integration MAY choose the order of a list, read configuration and own a lifecycle callback, but MUST NOT be the only place a join exists, and MUST NOT add observable behavior of its own on top of one. The test is concrete: an application that uses no container MUST be able to assemble the same server, and MUST observe the same ordering, failures and ownership.

**B-COMPOSE-02**: Composition MUST NOT add a capability to the components it joins. It introduces no second execution queue, no scheduler, no discovery, no automatic retries, no authentication and no durable delivery. Admission, ordering, drain, error policy and resource ownership stay as the joined components define them; this specification only says where those contracts meet, and which side owns what at the seam.

**B-COMPOSE-03**: A join MUST own the whole of its own contract. When a hook, a phase or a cleanup step is part of what a join promises, the join performs it, rather than leaving part of it for the caller to repeat. A caller that forgets a step it was never told about is a defect of the seam, not of the caller — and a split contract is also what makes a second integration diverge from the first.

For example, the RPC adapter of §2 calls all four configurer hooks itself. An earlier arrangement had the container call the first hook and the adapter call the other three; that is the shape B-COMPOSE-03 forbids, because a container-free caller had no way to know the first one existed.

## 2. RPC-to-Runtime adapter

**B-RPC-01**: An explicitly assembled RPC adapter owns business codec and protocol lookup and Runtime dispatch. It decodes borrowed transport bodies into independent objects before ordinary Handler admission, and dispatches with the received Route Key, business identity, Metadata and logical origin. Handler declarations select the Domain — the transport does not carry it and the adapter MUST NOT infer one — and the adapter MUST NOT fabricate a client Connection for work that arrived over RPC.

**B-RPC-02**: Call response types come from request bindings. A call requires the caller's current Runtime Route; its registered success and failure continuations return to that exact source Route and participate in drain. A synchronous rejection MUST NOT leave a continuation reserved, and MUST report the failure once: the caller learns of it from the rejection itself, not additionally through the continuation. Object replies preserve the RPC source-Slot preference and replacement-connection fallback; a Notify cannot be replied to. A business return value does not automatically reply, and an error raised after business admission keeps ordinary Runtime error policy — an application that wants the caller to see an error replies with one explicitly.

**B-RPC-03**: The adapter owns the Node it builds. Composition hooks run in one declared order: adjust transport settings, then wrap the adapter's own handler, then, after build and before start, attach. Detach runs in reverse order before the Node closes. A failing attach MUST detach what already attached, in reverse, and close the unstarted Node, so a half-attached Node is never handed back. Closing is idempotent. The adapter replaces any handler a hook installed, because dispatch into Runtime is the thing the adapter is for; a hook that needs to see traffic wraps the handler instead.

**B-RPC-04**: When Runtime refuses an inbound Call before executing it — admission closed, executor overloaded, invalid Domain or Route Key, no Handler — the adapter MUST answer at once with that refusal's own framework error code, not with a generic handler error. Rationale: a refused request never ran, so the caller may safely retry it on another instance, which it may not do after "ran and failed". During rolling restarts and overload this is exactly the distinction a caller needs, and a generic handler error erases it. An inbound Notify has nobody to tell, and its refusal is recorded only as a diagnostic. Unknown commands, decode failures and argument-binding failures are not Runtime's pre-execution refusals and keep RPC's handler-error policy.

For example, a service-role routing layer wraps the adapter's handler, receives it as its own direct handler, starts against the built Node in attach and closes in detach. Routed requests then reach the routing layer rather than Runtime dispatch, and how they enter Runtime afterwards is application code.

## 3. Shutdown budget

**B-CLOSE-01**: The phases of shutdown MUST be budgeted together, not configured apart. Shutdown runs them in sequence — leave rotation, drain admitted work, flush the last changes — so what a deployment has to cover is the sum, and a profile that configures each phase separately produces a total nobody chose. The total is therefore the configured value and the drain is derived from it, so the sum cannot drift. Two consequences are normative. The flush keeps a guaranteed slice rather than whatever the drain leaves, because losing the last writes is worse than cutting a drain short, and the drain is the phase that only waits while the flush is the phase that writes. And the default total MUST be chosen below the grace period an ordinary orchestrator applies before it kills the process, because a budget larger than that one is not a long shutdown but a lost one: the kill lands inside the drain and the flush never runs. A split that leaves the drain nothing MUST fail at startup.

**B-CLOSE-02**: The first phase reports unreadiness while work is still admitted. Readiness and admission MUST NOT change in the same instant: a load balancer learns of unreadiness only at its next probe, so an instance that stops accepting at the moment it starts reporting refuses everything sent in between. For a configurable lead the profile therefore reports failure for the rotation condition of B-OBS-02 and keeps admitting, serving what arrives normally; a Timer or Cron due in that window still fires. The lead has no default, because only a deployment knows its probe interval; a deployment behind a load balancer SHOULD set it to at least one interval, and one without a readiness probe SHOULD leave it at zero. After the lead, admission closes, which also stops future scheduling: once admission is closed a fired Timer's submission is refused and dropped, so keeping the scheduler running through the drain would discard rather than execute, and durable timers that outlive a restart remain a separate unimplemented capability.

Whoever triggers the sequence — a container's close event, a signal handler, an application's own `main` — observes the same phases in the same order. The trigger is integration work; the budget and the phases are not.

<a id="4-observability-composition"></a>

## 4. Observability composition

Core defines what a reading is and each component defines which readings it has; composition is where they are collected and where "should this instance receive traffic" is answered.

**B-OBS-01**: Composition publishes one reading source per assembled component that is present, each under a fixed prefix — `runtime`, `data`, `rpc`, `network` — so a dashboard reads any deployment of this framework. A component that is not assembled publishes nothing rather than publishing zeros, because a missing component and an idle one are different. A component the application built itself, outside the assembly, is its own to publish.

**B-OBS-02**: Readiness is a separate question from readings and MUST NOT be derived from them by a threshold. It is a set of named conditions, each either holding or not; the result is ready only when every condition holds, and when it is not, the failing conditions MUST be named. Composition contributes a condition for each assembled component that can stop accepting work — admission open, changes accepted — and nothing else: whether an instance is ready to serve players also depends on policy the framework does not own, such as attachment to a Router or a loaded world, which the application contributes as its own conditions.

**B-OBS-03**: Readiness and readings MUST be answerable while the instance is in trouble. They are therefore served in front of business dispatch, outside any business execution queue, and a condition MUST answer from state it already has without blocking, dispatching or taking a lock a saturated instance could be holding. A condition that fails to answer counts as not ready. This is the whole point: an endpoint that queues behind the work it reports on says nothing at the one moment it matters.

**B-OBS-04**: Shutdown is visible. Draining closes admission before work stops, so readiness reports not ready while the instance is still finishing accepted work — which is what lets a load balancer stop sending before the process goes away. Readings keep answering throughout, including after admission closed.

**B-OBS-05**: Exposition is not specified here. Composition MAY serve readings in a format of its choosing; an application that needs a particular one implements the Core sink and maps the names. Paths, when served over HTTP, are absolute and independent of any configured business path prefix, so a probe does not need to know it, and a prefix neither exposes nor hides them. Only the read method is served; any other method is ordinary business traffic.

<a id="5-business-path-prefix"></a>

## 5. Business path prefix

**B-HTTP-01**: An application MAY place all of its business HTTP endpoints under a common prefix without its endpoint declarations knowing about it. Matching uses a complete raw path segment, preserves the query, and never silently decodes or redirects a different path; a request outside the prefix receives 404 without business execution. The endpoint observes the prefix-relative target. Mapping MUST NOT mutate the request the transport owns: an implementation that needs a different target produces its own view and releases it, including when dispatch fails, and leaves the original untouched for the transport to release. An invalid prefix is a configuration error where it is configured, not a request-time failure.

For example, prefix `/game` maps `/game/status?id=7` to the registered `/status` endpoint with the same `id=7` query; `/games/status` and `/game%2Fstatus` do not match, and `/game` and `/game/` both map to `/`. The observability paths of §4 are matched before this prefix is applied, so a probe reaches them at the server root whatever the prefix is.

## 6. Validation and limits

Java validation entries: [BootRpcCompositionTest](../../game-boot/src/test/java/cn/managame/boot/rpc/BootRpcCompositionTest.java) assembles the §2 adapter with no container and exercises inbound Notify and Call, outbound Call completion on the source Route, hook order and failed-attach cleanup; [ObservabilityEndpointsTest](../../game-boot/src/test/java/cn/managame/boot/metrics/ObservabilityEndpointsTest.java) covers §4; [HttpContextPathTest](../../game-boot/src/test/java/cn/managame/boot/http/HttpContextPathTest.java) covers §5; [ShutdownBudgetTest](../../game-boot/src/test/java/cn/managame/boot/lifecycle/ShutdownBudgetTest.java) covers the §3 derivation, defaults and rejected splits.

Not verified by these tests: production resource exhaustion, hung storage drivers, cross-process shutdown, deployment security, RPC capacity under load, and any exposition format other than the plain one the Java binding describes. The lead of B-CLOSE-02 is exercised through the container integration, which owns the trigger, in [MetricsAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/metrics/MetricsAssemblyTest.java).

## 7. Confirmed tradeoffs

| Decision | Why | Revisit when |
| --- | --- | --- |
| Composition is its own tier, not part of the container integration | A join that only ships inside one container's wiring is unavailable to every other application, and a second integration would have to reimplement it rather than reuse it | A join turns out to need something only a container can provide, which would mean it was never composition |
| A join owns all of its own hooks (B-COMPOSE-03) | A contract split between the join and its caller is invisible to a caller that was not told, and diverges between integrations | Never for this reason alone; a hook that genuinely needs container state belongs to the integration instead |
| Readiness is named conditions, not a threshold on readings | A threshold answers "is a number high" when the question is "should traffic come here", and gives no way to say which condition failed | A deployment needs a condition the framework cannot express as a boolean it already holds |
| The shutdown total is configured and the drain derived | Configuring phases apart produces a total nobody chose, and the kill lands inside whichever phase overran | An orchestrator exposes its grace period to the process, which would let the total be read rather than configured |
