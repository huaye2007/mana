# OGBS Container Integration Specification 1.0

**[English](OGBS-Spring-1.0.md)** | [简体中文](OGBS-Spring-1.0.zh-CN.md)

Language-independent responsibilities for optional application-container integration. The repository implements these in game-spring; companion: [Java development specification](OGBS-Spring-Java-25-Specification-1.0.md). Runtime/Data contracts remain authoritative and framework cores do not require a container.

## 1. Discovery and assembly

**S-REGISTER-01**: Discover business handlers, event listeners, HTTP endpoints, recurring task owners and repositories at startup within application-selected scope. Freeze registrations at Runtime/Data build. Discovery does not infer authentication, Domain identity, Route Key, business identity or serialization from class names. Application policy provides them explicitly. Invalid signatures, duplicate registrations and invalid scopes fail startup rather than silently omitting invalid declarations.

**S-REGISTER-02**: Register each discovered singleton owner once by object identity. An unrelated object must not be instantiated merely to determine whether it owns recurring tasks. Products whose type cannot be determined before initialization require explicit identifiable registration. Owners cannot synchronously require the same Runtime being constructed; this circular dependency rejects startup. Dynamic post-build discovery is outside this profile.

**S-DATA-01**: Container injection must expose the same initialized Repository instance owned by Data. Storage initialization precedes injection callbacks and business access. Preserve application names and qualifiers. Existing Data constructor/type/identity requirements apply. The container owns the application's data source; Data borrows it. Data cannot be closed while admitted Runtime work still needs repositories.

## 2. Lifecycle and failure

**S-HTTP-01**: Container-managed HTTP assembly connects discovered HTTP endpoints to the same Runtime admission, Domain and Route rules as ordinary business messages. Applications configure a listener port and an optional common context path; endpoint declarations remain relative to that prefix. Prefix matching uses a complete raw path segment, preserves the query, and never silently decodes or redirects a different path. Requests outside the prefix receive 404 without business execution. Configuration supplies size/time limits; invalid configuration and bind failure reject application startup rather than silently disabling the listener. No extra business executor is introduced.

**S-HTTP-02**: Start the managed listener after business registration and container singleton initialization. Default activation requires a discovered HTTP endpoint owner; explicit configuration can enable or disable it. The container owns the listener and resources it creates, while explicitly supplied network groups remain caller-owned. Stop it after the S-CLOSE-01 Runtime drain barrier, before storage destruction. Execution drain does not promise completion of response delivery or a deferred response outside registered Runtime work. Applications needing that boundary must register their continuations. Failed startup releases the managed listener and container-owned Runtime resources. The low-level Network HTTP API remains available for applications that assemble transport themselves.

For example, prefix `/game` maps `/game/status?id=7` to the registered `/status` endpoint with the same `id=7` query; `/games/status` and `/game%2Fstatus` do not match. The Handler observes the prefix-relative request URI. Listener lifecycle and path/reference validation are exercised by [HttpAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/runtime/HttpAssemblyTest.java); the Java binding defines property names and defaults.

**S-CLOSE-01**: On management-context shutdown, first stop Runtime admission and future scheduling, then wait for registered work and continuations, then close Runtime and dependent network/data resources. Preserve [RT-DRAIN](OGBS-Runtime-1.0.md#103-admission-stop-drain-and-observational-diagnostics) boundaries: delivery, unregistered callbacks and persistence are separate. Waiting is bounded by a configurable drain timeout: when it expires (for example a registered callback that is never completed, or hung business work), the profile logs how much work is unfinished and closes Runtime anyway, so shutdown cannot hang forever. Work still running at that point is abandoned, not cancelled.

**S-CLOSE-02**: The phases of shutdown MUST be budgeted together, not configured apart. Shutdown runs them in sequence — leave rotation, drain admitted work, flush the last changes — so what a deployment has to cover is the sum, and a profile that configures each phase separately produces a total nobody chose. The total is therefore the configured value and the drain is derived from it, so the sum cannot drift. Two consequences are normative. The flush keeps a guaranteed slice rather than whatever the drain leaves, because losing the last writes is worse than cutting a drain short, and the drain is the phase that only waits while the flush is the phase that writes. And the default total MUST be chosen below the grace period an ordinary orchestrator applies before it kills the process, because a budget larger than that one is not a long shutdown but a lost one: the kill lands inside the drain and the flush never runs. A split that leaves the drain nothing MUST fail at startup.

**S-CLOSE-03**: The first phase reports unreadiness while work is still admitted. Readiness and admission MUST NOT change in the same instant: a load balancer learns of unreadiness only at its next probe, so an instance that stops accepting at the moment it starts reporting refuses everything sent in between. For a configurable lead the profile therefore reports failure for the rotation condition of S-OBS-02 and keeps admitting, serving what arrives normally; a Timer or Cron due in that window still fires. The lead has no default, because only a deployment knows its probe interval; a deployment behind a load balancer SHOULD set it to at least one interval, and one without a readiness probe SHOULD leave it at zero. After the lead, admission closes as S-CLOSE-01 describes, which also stops future scheduling: once admission is closed a fired Timer's submission is refused and dropped, so keeping the scheduler running through the drain would discard rather than execute, and durable timers that outlive a restart remain a separate unimplemented capability.

For example, an admitted player Handler may still update its Repository after shutdown begins. Repository destruction waits until the Handler and its registered callbacks finish, then Data performs final write-back. New player requests reject at Runtime admission. Closing from that Handler's own Route is forbidden because it would wait for itself.

On startup failure, caller-owned resources remain subject to container cleanup; successful Runtime construction transfers executor lifecycle as specified by Runtime. Default event binding conflicts fail startup and close the newly constructed Runtime. Container integration neither sends unified TCP/RPC responses nor installs a business scheduler.

## 3. Validation and limits

Java validation entries: [CronBeansTest](../../game-spring/src/test/java/cn/managame/spring/runtime/CronBeansTest.java), [RuntimeLifecycleTest](../../game-spring/src/test/java/cn/managame/spring/runtime/RuntimeLifecycleTest.java), [repository integration tests](../../game-demo/src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java). Proxy policy and concrete shutdown delivery are defined in the Java binding. Production resource exhaustion, hung drivers, arbitrary container implementations, cross-process shutdown and hot registration are not verified by these tests.


## 4. Optional RPC integration

**S-RPC-01**: An explicitly enabled RPC adapter owns business codec/protocol lookup and Runtime dispatch. Decode borrowed transport bodies into independent objects before ordinary Handler admission with the received Key, identity, Metadata and logical origin. Handler declarations select Domain; the adapter must not fabricate a client Connection.

**S-RPC-02**: Call response types come from request bindings. Calls require the current Runtime Route; registered success/failure continuations return to the source Route and participate in drain. Synchronous rejection must not leak a continuation reservation. Object replies preserve RPC source-Slot preference and replacement-connection fallback; Notify cannot be replied to. Return values do not automatically reply, and errors after business admission retain ordinary Runtime error policy; applications explicitly reply with errors when needed.

**S-RPC-03**: The container starts Node after Runtime construction, and stops/drains Runtime before closing Node. Applications own thread-safe codecs and topology policy. This adds no discovery, automatic business retries, authentication, durable delivery or unified TCP/RPC send. For example, a Role Handler with RPC context uses a separate RPC reply entry, while its outbound call completion returns to the originating Role Route. Java APIs/configuration and real TCP validation are in [the Java specification](OGBS-Spring-Java-25-Specification-1.0.md#managed-rpc).

<a id="5-observability-composition"></a>

## 5. Observability composition

Core defines what a reading is and each component defines which readings it has; container integration is where they are collected and where "should this instance receive traffic" is answered.

**S-OBS-01**: Integration publishes one reading source per managed component that is present, each under a fixed prefix — `runtime`, `data`, `rpc`, `network` — so a dashboard reads any deployment of this framework. A component that is not assembled publishes nothing rather than publishing zeros, because a missing component and an idle one are different. A component the application built itself, outside the container, is its own to publish.

**S-OBS-02**: Readiness is a separate question from readings and MUST NOT be derived from them by a threshold. It is a set of named conditions, each either holding or not; the result is ready only when every condition holds, and when it is not, the failing conditions MUST be named. Integration contributes a condition for each managed component that can stop accepting work — admission open, changes accepted — and nothing else: whether an instance is ready to serve players also depends on policy the framework does not own, such as attachment to a Router or a loaded world, which the application contributes as its own conditions.

**S-OBS-03**: Readiness and readings MUST be answerable while the instance is in trouble. They are therefore served in front of business dispatch, outside any business execution queue, and a condition MUST answer from state it already has without blocking, dispatching or taking a lock a saturated instance could be holding. A condition that fails to answer counts as not ready. This is the whole point: an endpoint that queues behind the work it reports on says nothing at the one moment it matters.

**S-OBS-04**: Shutdown is visible. Draining closes admission before work stops, so readiness reports not ready while the instance is still finishing accepted work — which is what lets a load balancer stop sending before the process goes away. Readings keep answering throughout, including after admission closed.

**S-OBS-05**: Exposition is not specified here. Integration MAY serve readings in a format of its choosing; an application that needs a particular one implements the Core sink and maps the names. Paths, when served over HTTP, are absolute and independent of any configured business context path, so a probe does not need to know it.
