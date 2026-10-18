# OGBS Container Integration Specification 1.0

**[English](OGBS-Spring-1.0.md)** | [简体中文](OGBS-Spring-1.0.zh-CN.md)

Language-independent responsibilities for optional application-container integration. The repository implements these in game-spring; companion: [Java development specification](OGBS-Spring-Java-25-Specification-1.0.md). Runtime/Data contracts remain authoritative and framework cores do not require a container.

This document covers only what a container adds: finding business declarations, supplying configuration, injecting initialized objects and owning lifecycle callbacks. The joins between components — the RPC-to-Runtime adapter, the shutdown budget, observability collection, business path prefixing — are [server composition](OGBS-Boot-1.0.md) and exist without a container; this integration enables and wires them, and per [B-COMPOSE-01](OGBS-Boot-1.0.md#1-what-this-tier-is) MUST NOT add observable behavior of its own on top of one.

## 1. Discovery and assembly

**S-REGISTER-01**: Discover business handlers, event listeners, HTTP endpoints, recurring task owners and repositories at startup within application-selected scope. Freeze registrations at Runtime/Data build. Discovery does not infer authentication, Domain identity, Route Key, business identity or serialization from class names. Application policy provides them explicitly. Invalid signatures, duplicate registrations and invalid scopes fail startup rather than silently omitting invalid declarations.

**S-REGISTER-02**: Register each discovered singleton owner once by object identity. An unrelated object must not be instantiated merely to determine whether it owns recurring tasks. Products whose type cannot be determined before initialization require explicit identifiable registration. Owners cannot synchronously require the same Runtime being constructed; this circular dependency rejects startup. Dynamic post-build discovery is outside this profile.

**S-DATA-01**: Container injection must expose the same initialized Repository instance owned by Data. Storage initialization precedes injection callbacks and business access. Preserve application names and qualifiers. Existing Data constructor/type/identity requirements apply. The container owns the application's data source; Data borrows it. Data cannot be closed while admitted Runtime work still needs repositories.

## 2. Lifecycle and failure

**S-HTTP-01**: Container-managed HTTP assembly connects discovered HTTP endpoints to the same Runtime admission, Domain and Route rules as ordinary business messages. Applications configure a listener port and an optional common path prefix; prefix mapping itself is [B-HTTP-01](OGBS-Boot-1.0.md#5-business-path-prefix) and this integration only supplies the configured value and installs the mapping in front of business dispatch. Configuration supplies size/time limits; invalid configuration and bind failure reject application startup rather than silently disabling the listener. No extra business executor is introduced.

**S-HTTP-02**: Start the managed listener after business registration and container singleton initialization. Default activation requires a discovered HTTP endpoint owner; explicit configuration can enable or disable it. The container owns the listener and resources it creates, while explicitly supplied network groups remain caller-owned. Stop it after the S-CLOSE-01 Runtime drain barrier, before storage destruction. Execution drain does not promise completion of response delivery or a deferred response outside registered Runtime work. Applications needing that boundary must register their continuations. Failed startup releases the managed listener and container-owned Runtime resources. The low-level Network HTTP API remains available for applications that assemble transport themselves.

Listener lifecycle and path/reference validation are exercised by [HttpAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/runtime/HttpAssemblyTest.java); the Java binding defines property names and defaults, and [HttpContextPathTest](../../game-boot/src/test/java/cn/managame/boot/http/HttpContextPathTest.java) covers the mapping rules themselves.

**S-CLOSE-01**: On management-context shutdown, first stop Runtime admission and future scheduling, then wait for registered work and continuations, then close Runtime and dependent network/data resources. Preserve [RT-DRAIN](OGBS-Runtime-1.0.md#103-admission-stop-drain-and-observational-diagnostics) boundaries: delivery, unregistered callbacks and persistence are separate. Waiting is bounded: when the bound expires (for example a registered callback that is never completed, or hung business work), the profile logs how much work is unfinished and closes Runtime anyway, so shutdown cannot hang forever. Work still running at that point is abandoned, not cancelled.

**S-CLOSE-02**: The container owns the trigger, not the phases. A management-context close event begins the sequence of [B-CLOSE-01](OGBS-Boot-1.0.md#3-shutdown-budget), and the bound of S-CLOSE-01 is that budget's derived drain rather than a separately configured timeout. The integration MUST run the sequence synchronously, before ordinary close listeners and before destruction of the resources the drain depends on, and MUST handle only its own context. It MUST NOT reorder, shorten or skip a phase; a deployment that wants a different split configures the budget.

For example, an admitted player Handler may still update its Repository after shutdown begins. Repository destruction waits until the Handler and its registered callbacks finish, then Data performs final write-back. New player requests reject at Runtime admission. Closing from that Handler's own Route is forbidden because it would wait for itself.

On startup failure, caller-owned resources remain subject to container cleanup; successful Runtime construction transfers executor lifecycle as specified by Runtime. Container integration neither sends unified TCP/RPC responses nor installs a business scheduler. Default event binding lasts as long as the process while a container factory runs once per context, so the integration does not perform it; the application binds at process startup.

## 3. Validation and limits

Java validation entries: [CronBeansTest](../../game-spring/src/test/java/cn/managame/spring/runtime/CronBeansTest.java), [RuntimeLifecycleTest](../../game-spring/src/test/java/cn/managame/spring/runtime/RuntimeLifecycleTest.java), [repository integration tests](../../game-demo/src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java). Proxy policy and concrete shutdown delivery are defined in the Java binding. Production resource exhaustion, hung drivers, arbitrary container implementations, cross-process shutdown and hot registration are not verified by these tests.

<a id="4-optional-rpc-integration"></a>

## 4. Optional RPC integration

**S-RPC-01**: RPC integration is opt-in and consists of assembling the composition adapter of [B-RPC-01 to B-RPC-03](OGBS-Boot-1.0.md#2-rpc-to-runtime-adapter) from container inputs: a Runtime, a business codec, transport settings read from configuration, and an ordered list of composition hooks taken from declared extensions. The integration starts the Node after container initialization and stops it after the S-CLOSE-01 drain, so an inbound request never reaches a Runtime that is gone. Everything else about the adapter — codec and protocol lookup, dispatch, call and reply semantics, hook order, failed-attach cleanup — belongs to composition and is identical without a container. Applications own thread-safe codecs and topology policy. The integration adds no discovery, retries, authentication, durable delivery or unified TCP/RPC send.

Container ordering is the one thing this clause adds, and it is observable: hooks run in the container's declared order, so a routing extension that must be outermost declares itself last. Java APIs, configuration and real TCP validation are in [the Java specification](OGBS-Spring-Java-25-Specification-1.0.md#managed-rpc).

<a id="5-observability-composition"></a>

## 5. Observability composition

**S-OBS-01**: Observability integration is opt-in and consists of three wiring decisions: which managed components are present, so one reading source per present component is published under its fixed prefix; which readiness conditions exist, by contributing one per present component that can stop accepting work and collecting the application's own declared conditions in order; and where the endpoints are installed, which is in front of business dispatch and in front of any configured path prefix. The behaviour of all three — prefixes, named conditions, answering while saturated, visibility during shutdown, exposition — is [B-OBS-01 to B-OBS-05](OGBS-Boot-1.0.md#4-observability-composition) and does not change without a container.

Without this integration, components still keep their counters and an application reads them through their own snapshot API, or publishes them itself. A component the application built outside the container is its own to publish.

## 6. Confirmed tradeoffs

| Decision | Why | Revisit when |
| --- | --- | --- |
| The container tier carries only container concerns | A join that lives inside one container's wiring is unavailable to every other application; moving the joins out made them reachable and left this layer thin enough to read | A second container integration appears and needs something this layer still conflates |
| The container owns the shutdown trigger, not the phases | Only the container knows when its context is closing; nothing about the phases themselves depends on that | Never for this reason alone |
| Hook order comes from the container, hook semantics do not | Ordering declared extensions is exactly what a container is good at, and it is the only part of the adapter that a container can state better than a list | An integration needs an order the composition tier cannot express as a list |
